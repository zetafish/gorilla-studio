(ns gorilla.engine
  "The link to the Rust engine.

  Down: a watch on the song atom sends every change as patches.
  Up: the engine says hello on connect, then streams its playhead.

  The engine plays its own copy of the song, so nothing here is on the audio
  path: if this link drops, the music keeps playing. On reconnect the brain
  compares the engine's hello with what it last sent, and sends only what's
  missing: the changes made meanwhile to the same engine, or a full snapshot
  to a new one."
  (:require [clojure.java.io :as io]
            [gorilla.diff :as diff]
            [gorilla.proto-schema :as proto]
            [gorilla.wire :as wire]
            [malli.core :as m]
            [malli.error :as me])
  (:import (java.io DataInputStream File IOException)
           (java.net InetSocketAddress Socket)))

(def default-host "127.0.0.1")
(def default-port 7878)

(def ^:private valid-envelope? (m/validator proto/Envelope))
(def ^:private explain-envelope (m/explainer proto/Envelope))
(def ^:private valid-telemetry? (m/validator proto/Telemetry))

(defn frame
  "Validates `envelope` against the proto schema and encodes it as a frame."
  ^bytes [envelope]
  (when-not (valid-envelope? envelope)
    (throw (ex-info "Invalid envelope"
                    {:errors (me/humanize (explain-envelope envelope))})))
  (wire/encode envelope))

;; --- State ------------------------------------------------------------------

(defonce ^:private link (atom nil))       ; {:socket :out :song :engine-id} or nil
(defonce ^:private epoch (atom 0))        ; last epoch sent
(defonce ^:private synced (atom nil))     ; {:engine-id :song} the engine holds
(defonce ^:private write-lock (Object.))

(defonce playhead
  ;; Latest :engine/playhead from the engine, plus :received-at (epoch ms).
  (atom nil))

(defonce ^{:doc "Counts transport starts; each one restarts the engine's bar numbers."}
  transport-starts (atom 0))

(defonce ^{:doc "Transport state the brain wants; restored on a new engine."}
  desired-playing (atom true))

(defonce ^{:doc "Directory relative sample paths in the song are resolved against."}
  samples-root (atom (System/getProperty "user.dir")))

(defonce ^{:doc "The last :engine/rejected message, for inspection."}
  last-rejection (atom nil))

(defn connected? [] (some? @link))

(defn engine-id
  "The connected engine's random id, or nil."
  []
  (:engine-id @link))

(defn- log [& args] (apply println "gorilla.engine:" args))

;; --- Sending ----------------------------------------------------------------

(defn- lost! [current reason]
  (when (compare-and-set! link current nil)
    (log "lost the engine:" reason)))

(defn- send-envelope!
  "Sends one envelope. Returns true if it was written. Callers hold write-lock."
  [envelope]
  (when-let [{:keys [out] :as current} @link]
    (try
      (wire/write-frame! out (frame envelope))
      true
      (catch IOException e
        (lost! current (ex-message e))
        false))))

(defn sample-path
  "Absolute path for a sample in the song."
  [sample]
  (let [f (io/file sample)]
    (.getAbsolutePath (if (.isAbsolute f) f (io/file @samples-root sample)))))

(defn- resolve-samples
  "The song with every sample path absolute, as the engine sees it. Diffing
  resolved songs means a change of samples-root (opening a song elsewhere)
  reloads samples even when the relative paths look the same."
  [song]
  (reduce-kv (fn [s channel {[kind] :gen}]
               (if (= :sampler kind)
                 (update-in s [:channels channel :gen 1 :sample] sample-path)
                 s))
             song
             (:channels song)))

(defn- sync-to!
  "Sends whatever takes the engine from what it holds to `song`."
  [song]
  (locking write-lock
    (when-let [{:keys [engine-id]} @link]
      (let [song (resolve-samples song)
            ops (diff/diff (:song @synced) song)]
        (when (and (seq ops)
                   (send-envelope! {:epoch (swap! epoch inc) :ops ops}))
          (reset! synced {:engine-id engine-id :song song}))))))

(defn send-live!
  "Sends ops that aren't song changes (transport, scheduled bars) at the
  current epoch. Returns true if they were written."
  [ops]
  (locking write-lock
    (send-envelope! {:epoch @epoch :ops (vec ops)})))

(defn set-playing!
  "Starts (from the top) or stops the engine's transport. Transport isn't song
  data, so it doesn't touch the song, undo history or epoch. `then-ops` go in
  the same envelope, after the transport op, so bars scheduled there are
  queued before the first buffer plays."
  ([playing?] (set-playing! playing? []))
  ([playing? then-ops]
   (when-not (connected?)
     (throw (ex-info "Not connected to the engine" {})))
   (reset! desired-playing (boolean playing?))
   (when playing? (swap! transport-starts inc))
   (send-live! (into [{:op :transport/set :playing (boolean playing?)}] then-ops))))

;; --- Receiving --------------------------------------------------------------

(defn- read-telemetry [in]
  (let [msg (wire/read-frame in)]
    (when-not (valid-telemetry? msg)
      (throw (ex-info "Invalid telemetry" {:msg msg})))
    msg))

(def ^:private live-ops
  "Ops that aren't song changes: a rejected one needs no resync."
  #{"transport/set" "pattern/schedule-bar"})

(defn- rejected!
  "The engine refused an envelope and applied none of it. If it held song
  changes, the brain no longer knows what the engine has: resend everything."
  [{:keys [song]} {:keys [ops reason epoch] :as msg}]
  (reset! last-rejection msg)
  (log "the engine rejected epoch" epoch (pr-str ops) "-" reason)
  (let [ops (set ops)]
    (cond
      (contains? ops "song/reset")
      (do (log "it refused the whole song; it keeps playing what it had."
               "The next edit sends the whole song again.")
          (locking write-lock (swap! synced assoc :song nil)))

      (some (complement live-ops) ops)
      (do (log "resending the whole song")
          (locking write-lock (swap! synced assoc :song nil))
          (sync-to! @song)))))

(defn- start-reader!
  "Reads telemetry until the connection ends, then marks the link lost."
  [{:keys [^Socket socket in] :as current}]
  (doto (Thread.
         (fn []
           (try
             (loop []
               (let [msg (read-telemetry in)]
                 (case (:op msg)
                   :engine/playhead
                   (reset! playhead (assoc msg :received-at (System/currentTimeMillis)))
                   :engine/rejected
                   (rejected! current msg)
                   nil)
                 (recur)))
             (catch java.io.EOFException _
               (lost! current "it closed the connection"))
             (catch Exception e
               (when-not (.isClosed socket)
                 (lost! current (or (ex-message e) (str (type e)))))))))
    (.setName "gorilla-engine-telemetry")
    (.setDaemon true)
    (.start)))

;; --- Connecting -------------------------------------------------------------

(defn disconnect!
  "Closes the link. The engine keeps playing what it has."
  []
  (when-let [{:keys [^Socket socket song] :as current} @link]
    (remove-watch song ::engine)
    (compare-and-set! link current nil)
    (.close socket))
  nil)

(defn connect!
  "Connects to the engine, brings it up to date with `song-atom`, then sends a
  patch for every change. Throws if the engine isn't there."
  ([song-atom] (connect! song-atom {}))
  ([song-atom {:keys [host port timeout-ms]
               :or {host default-host port default-port timeout-ms 2000}}]
   (disconnect!)
   (let [socket (doto (Socket.)
                  (.setTcpNoDelay true)
                  (.connect (InetSocketAddress. ^String host (int port)) (int timeout-ms))
                  (.setSoTimeout (int timeout-ms)))
         in (DataInputStream. (.getInputStream socket))
         hello (try (read-telemetry in)
                    (catch Exception e (.close socket) (throw e)))
         _ (when-not (= :engine/hello (:op hello))
             (.close socket)
             (throw (ex-info "Engine did not say hello" {:msg hello})))
         {:keys [engine-id]} hello
         current {:socket socket :in in :out (.getOutputStream socket)
                  :song song-atom :engine-id engine-id}]
     (.setSoTimeout socket 0)
     (let [fresh? (locking write-lock
                    ;; Epochs only move forward, even across brains and engine restarts.
                    (swap! epoch max (:epoch hello))
                    (reset! link current)
                    ;; Same engine, nothing else written since: it still holds what we sent.
                    (when-not (and (= engine-id (:engine-id @synced))
                                   (= (:epoch hello) @epoch))
                      (reset! synced {:engine-id engine-id :song nil})
                      true))]
       (sync-to! @song-atom)
       ;; A new engine starts playing; put the transport back the way it was.
       (when (and fresh? (not @desired-playing))
         (send-live! [{:op :transport/set :playing false}])))
     (start-reader! current)
     (add-watch song-atom ::engine
                (fn [_ _ _ new]
                  ;; The song is already committed; never throw back into swap!.
                  (try
                    (sync-to! new)
                    (catch Exception e
                      (log "patch not sent:" (ex-message e))))))
     :connected)))

;; --- Staying connected ------------------------------------------------------

(defonce ^:private supervisor (atom nil))  ; {:running (atom bool) :process Process}

(def ^:private default-launch
  {:cmd ["cargo" "run" "-q" "-p" "gorilla-rt"]
   :dir "../engine"
   :log (io/file (System/getProperty "java.io.tmpdir") "gorilla-engine.log")})

(defn- launch! [{:keys [cmd dir addr] log-path :log}]
  (let [log-file ^File (io/file log-path)
        pb (doto (ProcessBuilder. ^java.util.List cmd)
             (.directory (io/file dir))
             (.redirectErrorStream true)
             (.redirectOutput (java.lang.ProcessBuilder$Redirect/appendTo log-file)))]
    (.put (.environment pb) "GORILLA_ENGINE_ADDR" addr)
    (log "starting the engine on" addr "- output in" (str log-file))
    (.start pb)))

(defn- alive? [^Process p] (and p (.isAlive p)))

(defn auto-connect!
  "Keeps `song-atom` connected to the engine, reconnecting (and resyncing)
  whenever the link drops. With `:launch` (true for the default `cargo run`,
  or a map like `default-launch`), also starts the engine when nothing is
  listening, so an engine crash heals itself."
  ([song-atom] (auto-connect! song-atom {}))
  ([song-atom {:keys [launch interval-ms] :or {interval-ms 500} :as opts}]
   (when-let [{:keys [running]} @supervisor] (reset! running false))
   (let [running (atom true)
         addr (str (:host opts default-host) ":" (:port opts default-port))
         launch (some-> (cond (map? launch) (merge default-launch launch)
                              launch default-launch)
                        (assoc :addr addr))
         state (atom {:running running :process nil})]
     (reset! supervisor state)
     (doto (Thread.
            (fn []
              (loop [warned? false]
                (when @running
                  (let [warned?
                        (if (connected?)
                          false
                          (try
                            (connect! song-atom (dissoc opts :launch :interval-ms))
                            (log "connected")
                            false
                            (catch Exception e
                              ;; At most one launch every few seconds, so a
                              ;; failing build doesn't spin.
                              (when (and launch
                                         (not (alive? (:process @state)))
                                         (< (+ (:launched-at @state 0) 5000)
                                            (System/currentTimeMillis)))
                                (swap! state assoc
                                       :process (launch! launch)
                                       :launched-at (System/currentTimeMillis)))
                              (when-not warned?
                                (log "waiting for the engine:" (or (ex-message e) (str (type e)))))
                              true)))]
                    (Thread/sleep (long interval-ms))
                    (recur warned?))))))
       (.setName "gorilla-engine-supervisor")
       (.setDaemon true)
       (.start))
     :auto-connecting)))

(defn stop-auto-connect!
  "Stops reconnecting and disconnects. An engine this brain started keeps
  running; `stop-engine!` ends it."
  []
  (when-let [{:keys [running]} (some-> @supervisor deref)]
    (reset! running false))
  (disconnect!))

(defn stop-engine!
  "Ends an engine process this brain started."
  []
  (when-let [^Process p (some-> @supervisor deref :process)]
    ;; `cargo run` leaves the engine as a child process: end that too.
    (doseq [^java.lang.ProcessHandle child (iterator-seq (.iterator (.descendants p)))]
      (.destroy child))
    (.destroy p)
    :stopped))
