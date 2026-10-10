(ns gorilla.wire-test
  (:require [clojure.test :refer [deftest is]]
            [gorilla.wire :as wire])
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
