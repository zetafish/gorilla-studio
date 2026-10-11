(ns gorilla.pattern
  "Generators for live patterns, all pure functions, so `(fn [bar] ...)`
  built from them is deterministic. Step generators (steps, euclid, rotate,
  ...) return vectors of 0/1; melodic ones (rhythm, transpose, notes) return
  notes: maps with :t :len :pitch :vel."
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

;; --- Notes ---------------------------------------------------------------------
;;
;; A note is {:t step :len steps :pitch midi :vel 0..1}. Steps are sixteenths:
;; :t is 0..15 within the bar, :len can run past it.

(def ^:private pitch-classes
  {"c" 0 "d" 2 "e" 4 "f" 5 "g" 7 "a" 9 "b" 11})

(defn note
  "MIDI number for a note name like :c4, :f#3 or :bb2 (C4 = 60), or a number
  as is."
  [x]
  (if (number? x)
    (int x)
    (let [s (name x)
          [_ letter accidental octave] (re-matches #"(?i)([a-g])(#|b|s)?(-?\d+)" s)]
      (when-not letter
        (throw (ex-info (str "Not a note name: " x) {:note x})))
      (+ (get pitch-classes (str/lower-case letter))
         (case accidental ("#" "s") 1 "b" -1 0)
         (* 12 (inc (parse-long octave)))))))

(def scales
  {:major [0 2 4 5 7 9 11]
   :minor [0 2 3 5 7 8 10]
   :dorian [0 2 3 5 7 9 10]
   :phrygian [0 1 3 5 7 8 10]
   :lydian [0 2 4 6 7 9 11]
   :mixolydian [0 2 4 5 7 9 10]
   :locrian [0 1 3 5 6 8 10]
   :harmonic-minor [0 2 3 5 7 8 11]
   :major-pentatonic [0 2 4 7 9]
   :minor-pentatonic [0 3 5 7 10]
   :blues [0 3 5 6 7 10]
   :chromatic (vec (range 12))})

(defn scale
  "The pitches of one octave of `mode` from `root`: (scale :a3 :minor)."
  [root mode]
  (let [intervals (or (scales mode)
                      (throw (ex-info (str "Unknown scale " mode) {:scale mode})))]
    (mapv #(+ (note root) %) intervals)))

(defn degree
  "The `n`th note of `scale` (0 is the root), wrapping into higher and lower
  octaves: (degree (scale :c4 :major) 7) is C5."
  [scale n]
  (let [size (count scale)]
    (+ (nth scale (mod n size)) (* 12 (#?(:clj Math/floorDiv :cljs (comp js/Math.floor /)) n size)))))

(def chords
  {:major [0 4 7] :minor [0 3 7] :dim [0 3 6] :aug [0 4 8]
   :sus2 [0 2 7] :sus4 [0 5 7] :7 [0 4 7 10] :maj7 [0 4 7 11] :m7 [0 3 7 10]})

(defn chord
  "Pitches of a chord: (chord :a3 :minor)."
  [root kind]
  (let [intervals (or (chords kind)
                      (throw (ex-info (str "Unknown chord " kind) {:chord kind})))]
    (mapv #(+ (note root) %) intervals)))

(defn- roll
  "A number in [0, 1) decided by `seed` and `i`."
  [seed i]
  (/ (mod (hash [seed i]) 10000) 10000.0))

(defn markov-walk
  "`len` pitches wandering through `scale`: each moves up or down by at most
  `max-step` scale degrees from the last, starting on degree `start`. The same
  seed always gives the same walk."
  [{:keys [seed len scale start max-step] :or {len 8 start 0 max-step 2}}]
  (let [span (inc (* 2 max-step))]
    (->> (range (dec len))
         (reductions (fn [d i] (+ d (- (int (* span (roll seed i))) max-step))) start)
         (mapv #(degree scale %)))))

(defn- duration->steps [d]
  (max 1 (#?(:clj Math/round :cljs js/Math.round) (double (* d length)))))

(defn rhythm
  "Places `pitches` one after another over a bar, with `durations` as
  fractions of a bar (1/4 is a beat, 1/16 a step), cycling both until the bar
  is full. A nil pitch is a rest. Each note lasts its duration."
  ([durations pitches] (rhythm durations pitches {}))
  ([durations pitches {:keys [vel] :or {vel 0.8}}]
   (loop [t 0, ds (cycle durations), ps (cycle pitches), out []]
     (if (or (>= t length) (empty? durations) (empty? pitches))
       out
       (let [steps (duration->steps (first ds))
             p (first ps)]
         (recur (+ t steps) (rest ds) (rest ps)
                (cond-> out
                  p (conj {:t t :len (min steps 64) :pitch (note p) :vel vel}))))))))

(defn transpose [notes semitones]
  (mapv #(update % :pitch + semitones) notes))

(defn notes?
  "True for a seq of note maps (a live pattern returning notes)."
  [x]
  (and (sequential? x) (seq x) (map? (first x))))

(defn notes
  "Normalizes notes for one bar: defaults (:len 1, :vel 0.8), whole numbers,
  pitches clamped to 0..127, notes outside the bar dropped, at most one per
  step (the later wins), sorted by step."
  [xs]
  (->> xs
       (keep (fn [{:keys [t len pitch vel] :or {len 1 vel 0.8}}]
               (when (and (number? t) (some? pitch))
                 (let [t (int t)]
                   (when (< -1 t length)
                     {:t t
                      :len (max 1 (min 64 (int len)))
                      :pitch (max 0 (min 127 (note pitch)))
                      :vel (max 0.0 (min 1.0 (double vel)))})))))
       (reduce (fn [m n] (assoc m (:t n) n)) (sorted-map))
       vals
       vec))
