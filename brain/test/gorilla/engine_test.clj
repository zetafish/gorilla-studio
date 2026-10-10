(ns gorilla.engine-test
  (:require [clojure.test :refer [deftest is]]
            [gorilla.engine :as engine]
            [gorilla.song :as song])
  (:import (java.io DataInputStream)
           (java.net ServerSocket)
           (java.util Arrays)
           (org.msgpack.core MessagePack MessageUnpacker)
           (org.msgpack.value Value)))

(defn- value->clj [^Value v]
  (cond
    (.isMapValue v) (into {} (map (fn [[k x]] [(value->clj k) (value->clj x)]))
                          (.map (.asMapValue v)))
    (.isArrayValue v) (mapv value->clj (.list (.asArrayValue v)))
    (.isStringValue v) (.asString (.asStringValue v))
    (.isIntegerValue v) (.asLong (.asIntegerValue v))
    (.isBooleanValue v) (.getBoolean (.asBooleanValue v))
    :else (str v)))

(defn- unpack [^bytes payload]
  (value->clj (.unpackValue ^MessageUnpacker (MessagePack/newDefaultUnpacker payload))))

(defn- read-frame [^DataInputStream in]
  (let [n (.readInt in)
        buf (byte-array n)]
    (.readFully in buf)
    (unpack buf)))

(def set-hat {:op :pattern/set-steps :pattern :drums :row :hat :steps (vec (repeat 16 1))})

(deftest frames-are-length-prefixed-msgpack-with-string-keywords
  (let [bytes (engine/frame {:epoch 3 :ops [set-hat]})
        n (.getInt (java.nio.ByteBuffer/wrap bytes))]
    (is (= n (- (alength bytes) 4)))
    (is (= {"epoch" 3
            "ops" [{"op" "pattern/set-steps" "pattern" "drums" "row" "hat"
                    "steps" (vec (repeat 16 1))}]}
           (unpack (Arrays/copyOfRange bytes 4 (alength bytes)))))))

(deftest invalid-envelopes-are-not-encoded
  (is (thrown? clojure.lang.ExceptionInfo
               (engine/frame {:epoch 1 :ops [(assoc set-hat :steps [1 2])]}))))

(deftest sends-a-snapshot-then-one-patch-per-change
  (with-open [server (ServerSocket. 0)]
    (let [song (atom song/default-song)
          _ (future (engine/connect! song {:port (.getLocalPort server)}))
          conn (.accept server)
          in (DataInputStream. (.getInputStream conn))
          snapshot (read-frame in)]
      (try
        (is (= ["hat" "kick" "snare"] (map #(get % "row") (get snapshot "ops"))))
        (swap! song assoc-in [:patterns :drums :steps :hat 1] 1)
        (let [patch (read-frame in)]
          (is (= (inc (get snapshot "epoch")) (get patch "epoch")))
          (is (= [["hat" [1 1 1 0 1 0 1 0 1 0 1 0 1 0 1 0]]]
                 (map (juxt #(get % "row") #(get % "steps")) (get patch "ops")))))
        (finally
          (engine/disconnect!)
          (.close conn))))))

(deftest transport-keeps-the-epoch-and-leaves-the-song-alone
  (with-open [server (ServerSocket. 0)]
    (let [song (atom song/default-song)
          _ (future (engine/connect! song {:port (.getLocalPort server)}))
          conn (.accept server)
          in (DataInputStream. (.getInputStream conn))
          snapshot (read-frame in)]
      (try
        (engine/set-playing! false)
        (is (= {"epoch" (get snapshot "epoch")
                "ops" [{"op" "transport/set" "playing" false}]}
               (read-frame in)))
        (is (= song/default-song @song))
        (finally
          (engine/disconnect!)
          (.close conn))))))

(deftest transport-needs-a-connection
  (engine/disconnect!)
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Not connected"
                        (engine/set-playing! true))))
