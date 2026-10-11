(ns gorilla.diff
  "Turns song changes into the smallest list of engine patches."
  (:require [gorilla.song :as song]))

(defn- generator->wire [[kind opts]]
  (assoc opts :type kind))

(defn diff
  "Patches that take the engine from `old` to `new`. With `old` nil the
  result is a full snapshot: it starts with `:song/reset`, so the engine ends
  up with exactly `new`."
  [old new]
  (let [rows (fn [s]
               (for [[pattern p] (sort-by key (:patterns s))
                     [row content] (sort-by first (song/rows p))]
                 [[pattern row] content]))
        old-rows (into {} (rows old))
        new-rows (rows new)
        new-row? (set (map first new-rows))]
    (vec
     (concat
      (when (nil? old) [{:op :song/reset}])
      (when (not= (:song/bpm old) (:song/bpm new))
        [{:op :song/set-bpm :bpm (double (:song/bpm new))}])
      (for [[channel {:keys [gen]}] (sort-by key (:channels new))
            :when (not= gen (get-in old [:channels channel :gen]))]
        {:op :channel/set :channel channel :generator (generator->wire gen)})
      (for [[[pattern row] {:keys [steps notes] :as content}] new-rows
            :when (not= content (old-rows [pattern row]))]
        (if steps
          {:op :pattern/set-steps :pattern pattern :row row :steps steps}
          {:op :pattern/set-notes :pattern pattern :row row
           :notes (mapv #(update % :vel double) notes)}))
      (for [[[pattern row]] (sort-by key old-rows)
            :when (and (not (new-row? [pattern row]))
                       (contains? (:patterns new) pattern)
                       (contains? (:channels new) row))]
        {:op :pattern/remove-row :pattern pattern :row row})
      (for [pattern (sort (keys (:patterns old)))
            :when (not (contains? (:patterns new) pattern))]
        {:op :pattern/remove :pattern pattern})
      ;; Removing a channel also removes its rows in the engine.
      (for [channel (sort (keys (:channels old)))
            :when (not (contains? (:channels new) channel))]
        {:op :channel/remove :channel channel})))))

(defn snapshot
  "Patches that bring any engine to exactly `song`."
  [song]
  (diff nil song))
