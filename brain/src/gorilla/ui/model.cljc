(ns gorilla.ui.model
  "What the UI shows and what a click means, with no UI code: plain functions
  from the song (and UI state) to view data, and from a position to a
  command. The HumbleUI layer only draws these and dispatches the commands,
  so all of this is testable without a window."
  (:require [gorilla.proto-schema :as proto]))

(def steps proto/pattern-steps)

;; --- Layout --------------------------------------------------------------------

(def layout
  {:row-h 30          ; one channel row in the rack
   :name-w 150        ; channel name column
   :lane-h 26         ; one pattern lane in the playlist
   :lane-name-w 110
   :min-bars 32})

(defn cell-w
  "Width of one step cell in a rack `width` wide."
  [width]
  (/ (- width (:name-w layout)) steps))

;; --- Rack ------------------------------------------------------------------------

(defn generator-label [[kind {:keys [voice sample]}]]
  (case kind
    :builtin (name voice)
    :sampler (let [s (str sample)
                   i (inc (max (.lastIndexOf s "/") -1))]
               (subs s i))
    (str kind)))

(defn rack
  "One row per channel, sorted by id, with its row in `pattern`:
  {:channel :gen-label :kind (:steps | :notes | :none) :steps :notes :live?}.
  `live` is the set of [pattern row] keys with a live pattern; `live-bars`
  maps [pattern row] to what the live pattern sends for the bar playing now."
  [song pattern live live-bars]
  (let [p (get-in song [:patterns pattern])]
    (vec
     (for [[channel {:keys [gen]}] (sort-by key (:channels song))
           :let [k [pattern channel]
                 played (get live-bars k)
                 steps (get-in p [:steps channel])
                 notes (get-in p [:notes channel])]]
       {:channel channel
        :gen-label (generator-label gen)
        :kind (cond steps :steps notes :notes :else :none)
        :steps steps
        :notes notes
        :live? (contains? live k)
        ;; What's actually playing this bar, when a live pattern drives the row.
        :played played}))))

(defn rack-hit
  "The row index and step under (x, y) in a rack `width` wide, or nil."
  [width x y]
  (let [row (int (Math/floor (/ y (:row-h layout))))
        step (int (Math/floor (/ (- x (:name-w layout)) (cell-w width))))]
    (when (and (>= row 0) (< -1 step steps) (>= x (:name-w layout)))
      {:row row :step step})))

(defn- toggle-note [notes step]
  (if (some #(= step (:t %)) notes)
    (filterv #(not= step (:t %)) notes)
    (let [pitch (or (:pitch (last (sort-by :t notes))) 60)]
      (vec (sort-by :t (conj notes {:t step :len 1 :pitch pitch :vel 0.8}))))))

(defn click-step
  "The command for clicking `step` of `row` (a rack row) in `pattern`."
  [pattern {:keys [channel kind notes]} step]
  (case kind
    :steps {:cmd :pattern/toggle-step :pattern pattern :row channel :step step}
    :notes {:cmd :pattern/set-notes :pattern pattern :row channel
            :notes (toggle-note (vec notes) step)}
    :none {:cmd :pattern/add-row :pattern pattern :row channel
           :steps (assoc (vec (repeat steps 0)) step 1)}))

;; --- Playlist ----------------------------------------------------------------------

(defn song-bars
  "Bars up to the end of the last clip; 0 without a playlist."
  [song]
  (reduce max 0 (map #(+ (:start %) (:len %)) (:playlist song))))

(defn visible-bars
  "Bars the playlist view shows: the song plus room to extend it, in whole
  groups of 8."
  [song]
  (let [n (max (:min-bars layout) (+ 8 (song-bars song)))]
    (* 8 (int (Math/ceil (/ n 8))))))

(defn lanes
  "One lane per pattern, sorted by id, with its clips."
  [song]
  (vec (for [pattern (sort (keys (:patterns song)))]
         {:pattern pattern
          :clips (filterv #(= pattern (:pattern %)) (:playlist song))})))

(defn playlist-hit
  "The lane index and bar under (x, y) in a playlist `width` wide showing
  `bars` bars, or nil."
  [width bars x y]
  (let [bar-w (/ (- width (:lane-name-w layout)) bars)
        lane (int (Math/floor (/ y (:lane-h layout))))
        bar (int (Math/floor (/ (- x (:lane-name-w layout)) bar-w)))]
    (when (and (>= lane 0) (< -1 bar bars) (>= x (:lane-name-w layout)))
      {:lane lane :bar bar})))

(def default-clip-bars 4)

(defn click-playlist
  "Clicking a clip removes it; clicking an empty bar adds a clip there, as
  long as it fits before the lane's next clip (at most `default-clip-bars`)."
  [{:keys [pattern clips]} bar]
  (if-let [clip (first (filter #(<= (:start %) bar (dec (+ (:start %) (:len %)))) clips))]
    {:cmd :playlist/remove :pattern pattern :start (:start clip)}
    (let [next-start (reduce min ##Inf
                             (keep #(when (> (:start %) bar) (:start %)) clips))]
      {:cmd :playlist/add :pattern pattern :start bar
       :len (max 1 (min default-clip-bars (- next-start bar)))})))

(defn song-position
  "Where `bar` (counted since play) falls in the arrangement, or nil
  without a playlist."
  [song bar]
  (let [n (song-bars song)]
    (when (and (pos? n) bar)
      (if (:song/loop song) (mod bar n) bar))))

(defn active-patterns
  "Patterns the engine plays at arrangement position `pos`; all of them
  without a playlist."
  [song pos]
  (if (empty? (:playlist song))
    (set (keys (:patterns song)))
    (set (keep #(when (and pos (<= (:start %) pos (dec (+ (:start %) (:len %)))))
                  (:pattern %))
               (:playlist song)))))

;; --- Naming ------------------------------------------------------------------------

(defn next-name
  "The first of prefix1, prefix2, ... not already a key of `taken`."
  [prefix taken]
  (first (remove #(contains? taken %) (map #(keyword (str prefix %)) (iterate inc 1)))))
