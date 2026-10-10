(ns gorilla.engine
  "The link to the Rust engine. A watch on the song atom diffs every change and
  sends the patches as length-prefixed MessagePack frames over loopback TCP.

  The engine plays its own copy of the song, so nothing here is on the audio
  path: if this link drops, the music keeps playing."
  (:require [gorilla.diff :as diff]
            [gorilla.proto-schema :as proto]
            [malli.core :as m]
            [malli.error :as me])
  (:import (java.io OutputStream)
           (java.net InetSocketAddress Socket)
           (java.nio ByteBuffer)
           (org.msgpack.core MessagePack MessagePacker)))

(def default-host "127.0.0.1")
(def default-port 7878)

(def ^:private valid-envelope? (m/validator proto/Envelope))
(def ^:private explain-envelope (m/explainer proto/Envelope))

(defn- pack! [^MessagePacker p x]
  (cond
    (map? x) (do (.packMapHeader p (count x))
                 (doseq [[k v] x] (pack! p k) (pack! p v)))
    (sequential? x) (do (.packArrayHeader p (count x))
                        (doseq [v x] (pack! p v)))
    ;; Keywords travel as their full name: :pattern/set-steps -> "pattern/set-steps".
    (keyword? x) (.packString p (subs (str x) 1))
    (string? x) (.packString p ^String x)
    (int? x) (.packLong p (long x))
    (double? x) (.packDouble p (double x))
    (boolean? x) (.packBoolean p (boolean x))
    (nil? x) (.packNil p)
    :else (throw (ex-info (str "Cannot encode " (type x)) {:value x}))))

(defn frame
  "Validates `envelope` against the proto schema and encodes it as a frame:
  a 4-byte big-endian length followed by the MessagePack payload."
  ^bytes [envelope]
  (when-not (valid-envelope? envelope)
    (throw (ex-info "Invalid envelope"
                    {:errors (me/humanize (explain-envelope envelope))})))
  (let [packer (MessagePack/newDefaultBufferPacker)
        _ (pack! packer envelope)
        payload (.toByteArray packer)
        n (alength payload)]
    (when (> n proto/max-frame-bytes)
      (throw (ex-info "Envelope too large" {:bytes n :max proto/max-frame-bytes})))
    (-> (ByteBuffer/allocate (+ 4 n))
        (.putInt n)
        (.put payload)
        (.array))))

(defonce ^:private link (atom nil))

(defonce ^:private epoch (atom 0))

(defn connected? [] (some? @link))

(defn- send-envelope!
  "Sends one envelope. Returns true if it was written."
  [envelope]
  (when-let [{:keys [^OutputStream out]} @link]
    (let [bytes (frame envelope)]
      (try
        (.write out bytes)
        (.flush out)
        true
        (catch java.io.IOException e
          (println "gorilla.engine: lost the engine:" (.getMessage e))
          (reset! link nil)
          false)))))

(defn- send-ops!
  "Sends song patches as the next epoch."
  [ops]
  (when (seq ops)
    (send-envelope! {:epoch (swap! epoch inc) :ops ops})))

(defn set-playing!
  "Starts (from the top) or stops the engine's transport. Transport isn't song
  data, so it doesn't touch the song, undo history or epoch."
  [playing?]
  (when-not (connected?)
    (throw (ex-info "Not connected to the engine" {})))
  (send-envelope! {:epoch @epoch
                   :ops [{:op :transport/set :playing (boolean playing?)}]}))

(defn disconnect!
  "Closes the link. The engine keeps playing what it has."
  []
  (when-let [{:keys [^Socket socket song]} @link]
    (remove-watch song ::engine)
    (reset! link nil)
    (.close socket))
  nil)

(defn connect!
  "Connects to the engine, sends a full snapshot of `song-atom` and then a
  patch for every change to it."
  ([song-atom] (connect! song-atom {}))
  ([song-atom {:keys [host port timeout-ms]
               :or {host default-host port default-port timeout-ms 2000}}]
   (disconnect!)
   (let [socket (doto (Socket.)
                  (.setTcpNoDelay true)
                  (.connect (InetSocketAddress. ^String host (int port)) (int timeout-ms)))]
     (reset! link {:socket socket :out (.getOutputStream socket) :song song-atom})
     (send-ops! (diff/snapshot @song-atom))
     (add-watch song-atom ::engine
                (fn [_ _ old new]
                  ;; The song is already committed; never throw back into swap!.
                  (try
                    (send-ops! (diff/diff old new))
                    (catch Exception e
                      (println "gorilla.engine: patch not sent:" (ex-message e))))))
     :connected)))
