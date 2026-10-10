(ns gorilla.pattern-test
  (:require [clojure.test :refer [deftest is testing]]
            [gorilla.pattern :as p]))

(defn- s [x] (p/steps x))

(deftest steps-normalizes-to-one-bar
  (is (= 16 (count (s [1]))))
  (is (= (s "x...x...x...x...") (s "x...")) "short patterns repeat")
  (is (= (s [1 0 0 0]) (s [true false nil 0])))
  (is (= (s "x.x.") (s "x-x_ ")) "rests: . - _ and spaces are ignored")
  (is (= (vec (repeat 16 0)) (s []) (s nil)))
  (is (= (s (range 1 40)) (vec (repeat 16 1))) "long patterns are cut"))

(deftest euclidean-rhythms
  (is (= (s "x..x..x.") (p/euclid 3 8)) "tresillo")
  (is (= (s "x...x...x...x...") (p/euclid 4 16)))
  (is (= 5 (reduce + (p/euclid 5 16))))
  (is (= (p/rotate (p/euclid 3 8) 1) (p/euclid 3 8 1)))
  (testing "odd inputs don't throw"
    (is (= (s []) (p/euclid 0 16)))
    (is (= (s [1]) (p/euclid 20 16)))
    (is (vector? (p/euclid 3 0)))))

(deftest transforms
  (is (= (s ".x..") (p/rotate "x..." 1)))
  (is (= (s "...x") (p/rotate "x..." -1)))
  (is (= (s ".xxx") (p/invert "x...")))
  (is (= (s "x.x.") (p/overlay "x..." "..x.")))
  (is (= (s "x...") (p/mask "x.x." "xx.."))))

(deftest chance-is-deterministic-per-seed
  (is (= (p/chance 0.5 7) (p/chance 0.5 7)))
  (is (= (s []) (p/chance 0 7)))
  (is (= (s [1]) (p/chance 1 7)))
  (is (not= (p/chance 0.5 1) (p/chance 0.5 2))))

(deftest every-nth-bar
  (is (= [:a :b :b :b :a] (map #(p/every 4 % :a :b) (range 5)))))
