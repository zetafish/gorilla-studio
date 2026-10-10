(ns gorilla.diff-test
  (:require [clojure.test :refer [deftest is]]
            [gorilla.command :as command]
            [gorilla.diff :as diff]
            [gorilla.proto-schema :as proto]
            [gorilla.song :as song]
            [malli.core :as m]))

(def s song/default-song)

(deftest no-change-no-patches
  (is (= [] (diff/diff s s))))

(deftest toggling-a-step-sends-one-row
  (let [new (command/run s {:cmd :pattern/toggle-step :pattern :drums :row :hat :step 1})]
    (is (= [{:op :pattern/set-steps :pattern :drums :row :hat
             :steps [1 1 1 0 1 0 1 0 1 0 1 0 1 0 1 0]}]
           (diff/diff s new)))))

(deftest snapshot-covers-every-row-and-matches-the-proto-schema
  (let [ops (diff/snapshot s)]
    (is (= [:hat :kick :snare] (map :row ops)))
    (is (m/validate proto/Envelope {:epoch 1 :ops ops}))))
