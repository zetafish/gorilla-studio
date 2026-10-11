(ns gorilla.server-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [gorilla.live :as live]
            [gorilla.server :as server]
            [gorilla.song :as song]
            [gorilla.studio :as studio])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers WebSocket WebSocket$Listener)
           (java.util.concurrent LinkedBlockingQueue TimeUnit)))

(def ^:dynamic *port* nil)

(use-fixtures :each
  (fn [t]
    (live/clear!)
    (studio/reset-song!)
    (binding [*port* (server/start! 0)]
      (try (t) (finally (server/stop!) (studio/reset-song!))))))

(defn- connect
  "A WebSocket client: returns {:ws :inbox}, messages parsed as EDN."
  ([] (connect nil))
  ([origin]
   (let [inbox (LinkedBlockingQueue.)
         buf (StringBuilder.)
         listener (reify WebSocket$Listener
                    (onText [_ ws data last?]
                      (.append buf data)
                      (when last?
                        (.put inbox (edn/read-string (str buf)))
                        (.setLength buf 0))
                      (.request ws 1)
                      nil))
         builder (cond-> (.newWebSocketBuilder (HttpClient/newHttpClient))
                   origin (.header "Origin" origin))]
     {:ws (.join (.buildAsync builder (URI. (str "ws://127.0.0.1:" *port* "/ws")) listener))
      :inbox inbox})))

(defn- next-of
  "The next message of `type`, skipping others."
  [{:keys [inbox]} type]
  (loop []
    (let [msg (.poll ^LinkedBlockingQueue inbox 5 TimeUnit/SECONDS)]
      (cond (nil? msg) nil
            (= type (:type msg)) msg
            :else (recur)))))

(defn- send! [{:keys [ws]} msg]
  (.join (.sendText ^WebSocket ws (pr-str msg) true)))

(deftest a-client-gets-the-state-and-edits-the-song
  (let [c (connect)]
    (is (= song/default-song (:song (next-of c :state))))
    (is (contains? (next-of c :playhead) :connected?))
    (send! c {:type :command :cmd {:cmd :pattern/toggle-step :pattern :drums :row :hat :step 1}})
    (is (nil? (:status (next-of c :status))))
    (is (= 1 (get-in (next-of c :state) [:song :patterns :drums :steps :hat 1])))
    (testing "undo goes through the same history"
      (send! c {:type :action :action :undo :args []})
      (is (= song/default-song (:song (next-of c :state)))))))

(deftest refusals-come-back-as-status
  (let [c (connect)]
    (next-of c :state)
    (doseq [[msg expected] [[{:type :command :cmd {:cmd :song/set-bpm :bpm 5}} #"Invalid params"]
                            [{:type :action :action :open :args ["../../etc"]} #"No song called"]
                            [{:type :action :action :rm-rf :args []} #"Unknown action"]
                            [{:type :action :action :open :args []} #"takes 1 arguments"]
                            [{:type :nope} #"Unknown message type"]]]
      (send! c msg)
      (is (re-find expected (str (:status (next-of c :status)))) (pr-str msg)))
    (.join (.sendText ^WebSocket (:ws c) "#=(java.lang.System/exit 1)" true))
    (is (re-find #"Bad message" (:status (next-of c :status))))
    (is (= song/default-song @studio/song))))

(deftest unknown-web-pages-are-refused
  (is (thrown? Exception (connect "https://evil.example")))
  (is (some? (:ws (connect "tauri://localhost")))))

(deftest static-files-stay-inside-the-web-root
  (let [client (HttpClient/newHttpClient)
        get-status (fn [path]
                     (.statusCode (.send client (.build (HttpRequest/newBuilder (URI. (str "http://127.0.0.1:" *port* path))))
                                         (HttpResponse$BodyHandlers/ofString))))]
    (is (= 404 (get-status "/../../deps.edn")))
    (is (= 404 (get-status "/%2e%2e/%2e%2e/deps.edn")))))
