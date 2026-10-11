(ns gorilla.command
  "Every user-visible action is a command: an id, a malli params schema, a
  label and a pure (fn [song params] -> song). UI, REPL, MCP and shortcuts all
  go through `run`, or `gorilla.studio/dispatch!` for the stateful version."
  (:require [gorilla.proto-schema :as proto]
            [gorilla.song :as song]
            [malli.core :as m]
            [malli.error :as me]))

(defonce registry (atom {}))

(defn defcommand
  "Registers command `id`. `f` takes the song and the params map."
  [id {:keys [params label]} f]
  (swap! registry assoc id {:id id
                            :label label
                            :params params
                            :validate (m/validator params)
                            :explain (m/explainer params)
                            :fn f})
  id)

(defn run
  "Applies `cmd`, a map with `:cmd` and the command's params, to `song`.
  Returns the new song, or throws ex-info when the command is unknown, its
  params are invalid or the resulting song is invalid."
  [song {id :cmd :as cmd}]
  (let [{:keys [validate explain] f :fn} (or (get @registry id)
                                             (throw (ex-info (str "Unknown command " id)
                                                             {:cmd id})))
        params (dissoc cmd :cmd)]
    (when-not (validate params)
      (throw (ex-info (str "Invalid params for " id)
                      {:cmd id :errors (me/humanize (explain params))})))
    (let [new-song (f song params)]
      (when-not (song/valid? new-song)
        (throw (ex-info (str id " produced an invalid song")
                        {:cmd id :errors (me/humanize (song/explain new-song))})))
      new-song)))

(defn- existing-row! [song pattern row]
  (or (get-in song [:patterns pattern :steps row])
      (throw (ex-info (str "No row " row " in pattern " pattern)
                      {:pattern pattern :row row}))))

(def ^:private Step [:int {:min 0 :max (dec proto/pattern-steps)}])

(defcommand :pattern/toggle-step
  {:params [:map [:pattern :keyword] [:row :keyword] [:step Step]]
   :label "Toggle step"}
  (fn [song {:keys [pattern row step]}]
    (existing-row! song pattern row)
    (update-in song [:patterns pattern :steps row step] #(- 1 %))))

(defcommand :pattern/set-steps
  {:params [:map [:pattern :keyword] [:row :keyword] [:steps song/Steps]]
   :label "Set steps"}
  (fn [song {:keys [pattern row steps]}]
    (existing-row! song pattern row)
    (assoc-in song [:patterns pattern :steps row] steps)))

(defcommand :song/set-bpm
  {:params [:map [:bpm [:and number? [:>= 20] [:<= 999]]]]
   :label "Set tempo"}
  (fn [song {:keys [bpm]}]
    (assoc song :song/bpm bpm)))

(defcommand :channel/set
  {:params [:map [:channel :keyword] [:gen song/Generator]]
   :label "Set channel"}
  (fn [song {:keys [channel gen]}]
    (assoc-in song [:channels channel :gen] gen)))

(defn- remove-row [pattern row]
  (cond-> pattern
    (:steps pattern) (update :steps dissoc row)
    (:notes pattern) (update :notes dissoc row)))

(defcommand :channel/remove
  {:params [:map [:channel :keyword]]
   :label "Remove channel"}
  (fn [song {:keys [channel]}]
    (-> song
        (update :channels dissoc channel)
        (update :patterns update-vals #(remove-row % channel)))))

(defcommand :pattern/add-row
  {:params [:map [:pattern :keyword] [:row :keyword] [:steps {:optional true} song/Steps]]
   :label "Add row"}
  (fn [song {:keys [pattern row steps]}]
    (when-not (get-in song [:channels row])
      (throw (ex-info (str "No channel " row " for the row to play") {:row row})))
    (assoc-in song [:patterns pattern :steps row]
              (or steps (vec (repeat proto/pattern-steps 0))))))

(defcommand :pattern/remove-row
  {:params [:map [:pattern :keyword] [:row :keyword]]
   :label "Remove row"}
  (fn [song {:keys [pattern row]}]
    (if (get-in song [:patterns pattern])
      (update-in song [:patterns pattern] remove-row row)
      song)))

(defcommand :pattern/remove
  {:params [:map [:pattern :keyword]]
   :label "Remove pattern"}
  (fn [song {:keys [pattern]}]
    (-> song
        (update :patterns dissoc pattern)
        (update :playlist #(filterv (fn [c] (not= pattern (:pattern c))) %)))))

(defcommand :song/replace
  {:params [:map [:song :map]]
   :label "Replace song"}
  ;; The result is validated like any command's, so an invalid song is refused
  ;; whole. Undo brings the previous song back.
  (fn [_ {:keys [song]}]
    song))

(defcommand :pattern/set-notes
  {:params [:map [:pattern :keyword] [:row :keyword] [:notes song/Notes]]
   :label "Set notes"}
  ;; Creates the row (and pattern) if needed; a steps row becomes a notes row.
  (fn [song {:keys [pattern row notes]}]
    (when-not (get-in song [:channels row])
      (throw (ex-info (str "No channel " row " for the row to play") {:row row})))
    (-> (if (get-in song [:patterns pattern :steps row])
          (update-in song [:patterns pattern :steps] dissoc row)
          song)
        (assoc-in [:patterns pattern :notes row] notes))))

(defn- sort-clips [clips]
  (vec (sort-by (juxt :start #(:track % 0) :pattern) clips)))

(defcommand :playlist/set
  {:params [:map [:clips [:vector song/Clip]]]
   :label "Arrange"}
  (fn [song {:keys [clips]}]
    (assoc song :playlist (sort-clips clips))))

(defcommand :playlist/add
  {:params song/Clip
   :label "Add clip"}
  (fn [song clip]
    (update song :playlist #(sort-clips (conj % clip)))))

(defcommand :playlist/remove
  {:params [:map [:pattern :keyword] [:start [:int {:min 0}]]]
   :label "Remove clip"}
  (fn [song {:keys [pattern start]}]
    (update song :playlist
            (fn [clips] (filterv #(not (and (= pattern (:pattern %)) (= start (:start %)))) clips)))))

(defcommand :song/set-loop
  {:params [:map [:loop :boolean]]
   :label "Loop arrangement"}
  (fn [song {:keys [loop]}]
    (assoc song :song/loop loop)))
