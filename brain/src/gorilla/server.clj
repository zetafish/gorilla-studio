(ns gorilla.server
  "Serves UIs that run in another process (the web UI, in Tauri or a
  browser) over a WebSocket on loopback, and serves the built web UI itself.

  Down to the UI, as EDN text:
    {:type :state ...}     the song, live rows, song list, ... whenever they change
    {:type :playhead ...}  the playhead and engine status, ~30 times a second
    {:type :status ...}    the result of that client's last request
  Up from the UI:
    {:type :command :cmd {...}}           any command, through dispatch!
    {:type :action :action :save ...}     a gorilla.ui.actions action

  Commands are validated like any other, and land in undo history. The server
  binds to 127.0.0.1 only and refuses WebSockets from web pages it doesn't
  know, so a random site open in your browser can't drive it.

    clojure -M:serve      ; server + engine + nREPL; then open the UI"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [gorilla.engine :as engine]
            [gorilla.file :as file]
            [gorilla.live :as live]
            [gorilla.nrepl :as nrepl]
            [gorilla.studio :as studio]
            [gorilla.ui.actions :as actions]
            [org.httpkit.server :as http])
  (:import (java.io File)))

(def default-port 7880)

(def web-root "The built web UI." "../web/public")

(def ^:private max-message-chars (* 1024 1024))

(def playhead-interval-ms 33)

(defonce ^:private clients (atom #{}))

;; Set by watches; the send loop sends one fresh state message and clears it,
;; so a burst of changes (a command touches song and history) is sent once.
(defonce ^:private dirty (atom false))
(defonce ^:private server (atom nil))

;; --- Messages ---------------------------------------------------------------

(defn state-message []
  {:type :state
   :song @studio/song
   :live (set (keys @live/patterns))
   :file @file/current
   :songs (actions/song-folders)
   :samples (actions/sample-files)
   :builtins actions/builtins
   :can-undo? (boolean (seq (:past @studio/history)))
   :can-redo? (boolean (seq (:future @studio/history)))})

(defn playhead-message []
  {:type :playhead
   :connected? (engine/connected?)
   :playhead (select-keys @engine/playhead [:bar :step :playing])
   :live-bars (actions/live-bars)})

(defn- send! [ch msg]
  (http/send! ch (pr-str msg)))

(defn- broadcast! [msg]
  (let [text (pr-str msg)]
    (doseq [ch @clients]
      (http/send! ch text))))

(def ^:private actions
  "Actions a UI may ask for, with how many arguments each takes."
  {:play [actions/play! 0]
   :stop [actions/stop! 0]
   :toggle-play [actions/toggle-play! 0]
   :undo [actions/undo! 0]
   :redo [actions/redo! 0]
   :save [actions/save! 0]
   :open [actions/open! 1]
   :add-channel [actions/add-channel! 2]
   :add-pattern [(comp second actions/add-pattern!) 0]})

(defn handle
  "Runs one message from a UI. Returns the status reply."
  [{:keys [type cmd action args id]}]
  {:type :status
   :id id
   :status (case type
             :command (if (map? cmd) (actions/run! cmd) "A command must be a map")
             :action (let [[f arity] (get actions action)]
                       (cond
                         (nil? f) (str "Unknown action " action)
                         (not= arity (count args)) (str action " takes " arity " arguments")
                         :else (try (apply f args) (catch Exception e (ex-message e)))))
             (str "Unknown message type " type))})

(defn- read-message [text]
  (when (> (count text) max-message-chars)
    (throw (ex-info "Message too large" {})))
  ;; clojure.edn: data only, never evaluated.
  (edn/read-string {:readers {}} text))

;; --- HTTP ---------------------------------------------------------------------

(defn allowed-origin?
  "WebSockets from the Tauri app, from pages this server serves, from the
  shadow-cljs dev server, and from non-browser clients (no Origin)."
  [origin port]
  (or (nil? origin)
      (contains? #{"tauri://localhost" "http://tauri.localhost" "https://tauri.localhost"
                   (str "http://127.0.0.1:" port) (str "http://localhost:" port)
                   "http://localhost:8280" "http://127.0.0.1:8280"}
                 origin)))

(def ^:private content-types
  {"html" "text/html; charset=utf-8" "js" "text/javascript" "css" "text/css"
   "map" "application/json" "svg" "image/svg+xml" "png" "image/png" "ico" "image/x-icon"})

(defn- static-file
  "The file under `web-root` for a request path, or nil. Never leaves the root."
  ^File [uri]
  (let [root (.getCanonicalFile (io/file web-root))
        path (if (or (= uri "/") (str/blank? uri)) "/index.html" uri)
        f (.getCanonicalFile (io/file root (subs path 1)))]
    (when (and (.startsWith (.toPath f) (.toPath root)) (.isFile f))
      f)))

(defn- ws-handler [port req]
  (if-not (allowed-origin? (get-in req [:headers "origin"]) port)
    {:status 403 :body "Origin not allowed"}
    (http/as-channel req
                     {:on-open (fn [ch]
                                 (println "gorilla.server: UI connected from"
                                          (or (get-in req [:headers "origin"]) "a local client"))
                                 (swap! clients conj ch)
                                 (send! ch (state-message))
                                 (send! ch (playhead-message)))
                      :on-receive (fn [ch text]
                                    (send! ch (try (handle (read-message text))
                                                   (catch Exception e
                                                     {:type :status :status (str "Bad message: " (ex-message e))}))))
                      :on-close (fn [ch _]
                                  (println "gorilla.server: UI disconnected")
                                  (swap! clients disj ch))})))

(defn- app [port]
  (fn [{:keys [uri request-method] :as req}]
    (cond
      (= uri "/ws") (ws-handler port req)
      (not= :get request-method) {:status 405}
      :else (if-let [f (static-file uri)]
              {:status 200
               :headers {"Content-Type" (get content-types (last (str/split (.getName f) #"\.")) "application/octet-stream")
                         "Cache-Control" "no-cache"}
               :body f}
              {:status 404 :body "Not found. Build the web UI: cd web && npm run build"}))))

;; --- Lifecycle -------------------------------------------------------------------

(defn- watch! []
  (let [push (fn [_ _ old new] (when (not= old new) (reset! dirty true)))]
    (add-watch studio/song ::server push)
    (add-watch studio/history ::server push)
    (add-watch live/patterns ::server push)
    (add-watch file/current ::server push)))

(defn- start-send-loop!
  "Every `playhead-interval-ms`: the state if it changed, and the playhead if
  it (or the engine status) changed."
  [running]
  (future
    (loop [last-sent nil]
      (when @running
        (when (compare-and-set! dirty true false)
          (broadcast! (state-message)))
        (let [msg (playhead-message)
              sent (if (and (seq @clients) (not= msg last-sent))
                     (do (broadcast! msg) msg)
                     last-sent)]
          (Thread/sleep (long playhead-interval-ms))
          (recur sent))))))

(defn stop! []
  (when-let [{:keys [stop running]} @server]
    (reset! running false)
    (stop {:timeout 100})
    (reset! clients #{})
    (reset! server nil))
  nil)

(defn start!
  "Starts the server on 127.0.0.1. Port 0 picks a free one. Returns the port."
  ([] (start! default-port))
  ([port]
   (stop!)
   (let [actual (atom port)
         s (http/run-server (fn [req] ((app @actual) req))
                            {:ip "127.0.0.1" :port port :legacy-return-value? false})
         running (atom true)]
     (reset! actual (http/server-port s))
     (watch!)
     (start-send-loop! running)
     (reset! server {:stop (fn [& opts] (apply http/server-stop! s opts)) :running running :port @actual})
     @actual)))

(defn -main [& _]
  (try (nrepl/start!)
       (catch Exception e (println "No nREPL:" (ex-message e))))
  (engine/auto-connect! studio/song {:launch true})
  (let [port (start!)]
    (println (str "Gorilla Studio brain on http://127.0.0.1:" port
                  " (web UI) and ws://127.0.0.1:" port "/ws"))
    @(promise)))
