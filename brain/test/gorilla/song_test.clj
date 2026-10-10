(ns gorilla.song-test
  (:require [clojure.test :refer [deftest is]]
            [gorilla.song :as song]))

(deftest built-in-songs-are-valid
  (is (song/valid? song/empty-song))
  (is (song/valid? song/default-song)))

(deftest rows-must-have-sixteen-steps
  (is (not (song/valid? (assoc-in song/default-song [:patterns :drums :steps :hat] [1 0 1])))))
