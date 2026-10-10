(ns gorilla.diff
  "Turns song changes into the smallest list of engine patches.")

(defn- generator->wire [[kind opts]]
  (assoc opts :type kind))

(defn diff
  "Patches that take the engine from `old` to `new`. With `old` nil the
  result is a full snapshot: it starts with `:song/reset`, so the engine ends
  up with exactly `new`."
  [old new]
  (let [rows (fn [song]
               (for [[pattern {:keys [steps]}] (sort-by key (:patterns song))
                     [row row-steps] (sort-by key steps)]
                 [[pattern row] row-steps]))
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
      (for [[[pattern row] steps] new-rows
            :when (not= steps (old-rows [pattern row]))]
        {:op :pattern/set-steps :pattern pattern :row row :steps steps})
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
