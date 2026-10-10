(ns gorilla.pattern
  "Step generators for live patterns. Everything returns a vector of 0/1 and
  is a pure function, so `(fn [bar] ...)` built from these is deterministic."
  (:require [clojure.string :as str]
            [gorilla.proto-schema :as proto]))

(def length proto/pattern-steps)

(defn- on? [x]
  (cond
    (number? x) (not (zero? x))
    (char? x) (not (#{\. \- \_ \space} x))
    :else (boolean x)))

(defn steps
  "Normalizes anything step-like to a bar of `length` 0/1 steps: a seq of
  numbers or booleans, or a string where `.` `-` `_` and spaces are rests and
  anything else is a hit (\"x..x..x.\"). Shorter patterns repeat to fill the
  bar; longer ones are cut."
  [x]
  (let [xs (if (string? x) (seq (str/replace x #"\s+" "")) (seq x))]
    (if (empty? xs)
      (vec (repeat length 0))
      (into [] (comp (take length) (map #(if (on? %) 1 0))) (cycle xs)))))

(defn euclid
  "`k` hits spread as evenly as possible over `n` steps, optionally rotated
  right by `rotation`. (euclid 3 8) is the tresillo: x..x..x."
  ([k n] (euclid k n 0))
  ([k n rotation]
   (let [n (max 1 n)
         k (max 0 (min k n))
         base (mapv #(if (< (mod (* % k) n) k) 1 0) (range n))]
     (steps (take n (drop (mod (- rotation) n) (cycle base)))))))

(defn rotate
  "Rotates steps right by `n` (left when negative)."
  [xs n]
  (let [xs (steps xs)
        c (count xs)]
    (vec (take c (drop (mod (- n) c) (cycle xs))))))

(defn invert [xs] (mapv #(- 1 %) (steps xs)))

(defn overlay
  "A hit wherever any of the patterns has one."
  [& patterns]
  (apply mapv max (map steps patterns)))

(defn mask
  "Hits only where every pattern has one."
  [& patterns]
  (apply mapv min (map steps patterns)))

(defn chance
  "Each step is a hit with probability `p`, decided by `seed`: the same seed
  always gives the same bar, so pass the bar number for variation that
  repeats identically on every run."
  [p seed]
  (mapv (fn [i]
          (if (< (mod (hash [seed i]) 1000) (* 1000 p)) 1 0))
        (range length)))

(defn every
  "`then` on every `n`th bar (bar 0, n, 2n, ...), otherwise `else`."
  [n bar then else]
  (if (zero? (mod bar (max 1 n))) then else))
