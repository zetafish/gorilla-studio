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

(deftest note-names
  (is (= [60 69 54 46 61 0] (map p/note [:c4 :a4 :f#3 :bb2 :cs4 :c-1])))
  (is (= 64 (p/note 64)))
  (is (thrown? clojure.lang.ExceptionInfo (p/note :h4))))

(deftest scales-chords-and-degrees
  (is (= [57 59 60 62 64 65 67] (p/scale :a3 :minor)))
  (is (= [59 60 72 74 48] (map #(p/degree (p/scale :c4 :major) %) [-1 0 7 8 -7])))
  (is (= [57 60 64] (p/chord :a3 :minor)))
  (is (thrown? clojure.lang.ExceptionInfo (p/scale :c4 :klingon))))

(deftest markov-walks-stay-in-scale-and-repeat-per-seed
  (let [sc (p/scale :a3 :minor-pentatonic)
        walk (p/markov-walk {:seed 7 :len 32 :scale sc})
        in-scale? (set (for [o (range -3 4) n sc] (+ n (* 12 o))))]
    (is (= 32 (count walk)))
    (is (= (first sc) (first walk)) "starts on the start degree")
    (is (every? in-scale? walk))
    (is (= walk (p/markov-walk {:seed 7 :len 32 :scale sc})))
    (is (not= walk (p/markov-walk {:seed 8 :len 32 :scale sc})))))

(deftest rhythm-fills-one-bar
  (is (= [{:t 0 :len 4 :pitch 57 :vel 0.8}
          {:t 4 :len 2 :pitch 60 :vel 0.8}
          {:t 8 :len 8 :pitch 64 :vel 0.8}]
         (p/rhythm [1/4 1/8 1/8 1/2] [:a3 :c4 nil :e4])))
  (is (= 16 (count (p/rhythm [1/16] [60]))) "cycles until the bar is full")
  (is (= [] (p/rhythm [] [60]) (p/rhythm [1/4] []))))

(deftest notes-are-normalized
  (is (= [{:t 3 :len 1 :pitch 64 :vel 1.0}]
         (p/notes [{:t 3 :pitch :c4} {:t 3 :pitch 64 :vel 2} {:t 20 :pitch 1} {:pitch 60}])))
  (is (p/notes? [{:t 0 :pitch 60}]))
  (is (not (p/notes? [1 0 1 0])))
  (is (not (p/notes? []))))
