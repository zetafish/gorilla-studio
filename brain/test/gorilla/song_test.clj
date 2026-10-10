(ns gorilla.song-test
  (:require [clojure.test :refer [deftest is]]
            [gorilla.song :as song]))

(deftest empty-song-is-a-map
  (is (map? song/empty-song)))
