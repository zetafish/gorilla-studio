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
