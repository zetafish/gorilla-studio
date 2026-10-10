(ns gorilla.studio-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [gorilla.song :as song]
            [gorilla.studio :as studio]))

(use-fixtures :each (fn [t] (studio/reset-song!) (t) (studio/reset-song!)))

(def toggle {:cmd :pattern/toggle-step :pattern :drums :row :hat :step 1})

(deftest undo-and-redo
  (let [after (studio/dispatch! toggle)]
    (studio/undo!)
    (is (= song/default-song @studio/song))
    (studio/redo!)
    (is (= after @studio/song))
    (is (nil? (studio/redo!)))))

(deftest failed-commands-leave-everything-untouched
  (is (thrown? clojure.lang.ExceptionInfo (studio/dispatch! (assoc toggle :step 99))))
  (is (= song/default-song @studio/song))
  (is (= {:past [] :future []} @studio/history)))
