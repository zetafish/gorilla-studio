(ns gorilla.wire-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [clojure.walk :as walk]
            [gorilla.proto-schema :as proto]
            [gorilla.wire :as wire]
            [malli.core :as m]
            [malli.error :as me])
  (:import (java.io ByteArrayInputStream DataInputStream)
           (java.nio ByteBuffer)))

(defn- round-trip [x]
  (wire/read-frame (DataInputStream. (ByteArrayInputStream. (wire/encode x)))))

(deftest frames-are-length-prefixed
  (let [bytes (wire/encode {:a 1})]
    (is (= (- (alength bytes) 4) (.getInt (ByteBuffer/wrap bytes))))))

(deftest keys-and-ops-come-back-as-keywords
  (is (= {:op :pattern/set-steps :row "hat" :steps [1 0] :playing true :x nil :y 0.5}
         (round-trip {:op :pattern/set-steps :row :hat :steps [1 0]
                      :playing true :x nil :y 0.5}))))

(deftest oversized-frames-are-refused
  (is (thrown? clojure.lang.ExceptionInfo
               (wire/read-frame (DataInputStream. (ByteArrayInputStream. (byte-array [127 0 0 0])))))))

(defn- brain-shaped
  "Wire strings back to keywords, except fields that really are strings."
  [envelope]
  (walk/prewalk (fn [x]
                  (if (map? x)
                    (into {} (map (fn [[k v]]
                                    [k (if (and (string? v) (not (#{:sample :reason} k)))
                                         (keyword v)
                                         v)]))
                          x)
                    x))
                envelope))

(deftest every-rust-example-matches-the-malli-schema
  ;; Written by `gen-malli -- examples`; a Rust test keeps it current.
  (with-open [in (DataInputStream. (io/input-stream (io/resource "proto-examples.bin")))]
    (let [envelopes (loop [acc []]
                      (if (pos? (.available in))
                        (recur (conj acc (wire/read-frame in)))
                        acc))]
      (is (= 10 (count envelopes)))
      (doseq [e envelopes
              :let [e (brain-shaped e)]]
        (is (m/validate proto/Envelope e)
            (str (:ops e) " " (me/humanize (m/explain proto/Envelope e))))))))
