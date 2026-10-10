(ns gorilla.diff
  "Turns song changes into the smallest list of engine patches.")

(defn- set-steps [pattern row steps]
  {:op :pattern/set-steps :pattern pattern :row row :steps steps})

(defn diff
  "Patches that take the engine from `old` to `new`."
  [old new]
  (vec (for [[pattern {:keys [steps]}] (sort-by key (:patterns new))
             [row row-steps] (sort-by key steps)
             :when (not= row-steps (get-in old [:patterns pattern :steps row]))]
         (set-steps pattern row row-steps))))

(defn snapshot
  "Patches that bring a freshly started engine to `song`."
  [song]
  (diff nil song))
