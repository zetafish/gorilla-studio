(ns gorilla.diff-test
  (:require [clojure.test :refer [deftest is testing]]
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

(deftest snapshot-replaces-everything-and-matches-the-proto-schema
  (let [ops (diff/snapshot s)]
    (is (= [:song/reset :song/set-bpm] (map :op (take 2 ops))))
    (is (= {:op :channel/set :channel :hat :generator {:type :builtin :voice :hat}}
           (first (filter #(= :channel/set (:op %)) ops))))
    (is (= [:hat :kick :snare] (keep :row ops)))
    (is (m/validate proto/Envelope {:epoch 1 :ops ops}))))

(defn- run [song & cmds] (reduce command/run song cmds))

(def clap [:sampler {:sample "/samples/clap.wav"}])

(deftest structural-changes
  (testing "a new channel and its row"
    (let [new (run s {:cmd :channel/set :channel :clap :gen clap}
                   {:cmd :pattern/add-row :pattern :drums :row :clap :steps (vec (repeat 16 1))})]
      (is (= [{:op :channel/set :channel :clap :generator {:type :sampler :sample "/samples/clap.wav"}}
              {:op :pattern/set-steps :pattern :drums :row :clap :steps (vec (repeat 16 1))}]
             (diff/diff s new)))))
  (testing "removing a channel needs only the channel op"
    (is (= [{:op :channel/remove :channel :hat}]
           (diff/diff s (run s {:cmd :channel/remove :channel :hat})))))
  (testing "removing a row and a pattern"
    (is (= [{:op :pattern/remove-row :pattern :drums :row :hat}]
           (diff/diff s (run s {:cmd :pattern/remove-row :pattern :drums :row :hat}))))
    (is (= [{:op :pattern/remove :pattern :drums}]
           (diff/diff s (run s {:cmd :pattern/remove :pattern :drums})))))
  (testing "tempo"
    (is (= [{:op :song/set-bpm :bpm 128.0}]
           (diff/diff s (run s {:cmd :song/set-bpm :bpm 128})))))
  (testing "every diff is a valid envelope"
    (let [new (run s {:cmd :channel/set :channel :clap :gen clap}
                   {:cmd :pattern/add-row :pattern :fills :row :clap}
                   {:cmd :channel/remove :channel :snare}
                   {:cmd :song/set-bpm :bpm 90})]
      (is (m/validate proto/Envelope {:epoch 1 :ops (diff/diff s new)})))))

(deftest note-rows-become-set-notes
  (let [song (run s {:cmd :channel/set :channel :bass :gen [:builtin {:voice :bass}]}
                  {:cmd :pattern/set-notes :pattern :low :row :bass
                   :notes [{:t 0 :len 4 :pitch 33 :vel 1}]})
        ops (diff/diff s song)]
    (is (= {:op :pattern/set-notes :pattern :low :row :bass
            :notes [{:t 0 :len 4 :pitch 33 :vel 1.0}]}
           (last ops)) "velocity goes out as a double")
    (is (m/validate proto/Envelope {:epoch 1 :ops ops}))
    (testing "turning a steps row into notes is one op, not a removal"
      (is (= [{:op :pattern/set-notes :pattern :drums :row :hat :notes []}]
             (diff/diff s (run s {:cmd :pattern/set-notes :pattern :drums :row :hat :notes []})))))))
