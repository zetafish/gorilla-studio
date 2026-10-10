(ns gorilla.command-test
  (:require [clojure.test :refer [deftest is testing]]
            [gorilla.command :as command]
            [gorilla.song :as song]))

(def s song/default-song)

(defn- hat [song] (get-in song [:patterns :drums :steps :hat]))

(deftest toggle-step
  (let [toggled (command/run s {:cmd :pattern/toggle-step :pattern :drums :row :hat :step 1})]
    (is (= [1 1 1 0] (subvec (hat toggled) 0 4)))
    (is (= s (command/run toggled {:cmd :pattern/toggle-step :pattern :drums :row :hat :step 1})))))

(deftest set-steps
  (let [steps (vec (repeat 16 1))]
    (is (= steps (hat (command/run s {:cmd :pattern/set-steps :pattern :drums :row :hat :steps steps}))))))

(deftest invalid-commands-throw
  (testing "unknown command"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown command"
                          (command/run s {:cmd :nope}))))
  (testing "bad params"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid params"
                          (command/run s {:cmd :pattern/toggle-step :pattern :drums :row :hat :step 16}))))
  (testing "missing row"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No row"
                          (command/run s {:cmd :pattern/toggle-step :pattern :drums :row :cowbell :step 0}))))
  (testing "wrong step count"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid params"
                          (command/run s {:cmd :pattern/set-steps :pattern :drums :row :hat :steps [1]})))))
