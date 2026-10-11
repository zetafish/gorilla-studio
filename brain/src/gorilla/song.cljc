(ns gorilla.song
  "The song model: one immutable EDN value."
  (:require [gorilla.proto-schema :as proto]
            [malli.core :as m]))

(def Steps
  [:vector {:min proto/pattern-steps :max proto/pattern-steps} [:enum 0 1]])

(def Note
  "Times in steps (sixteenths): :t within the bar, :len may run past it."
  [:map {:closed true}
   [:t [:int {:min 0 :max (dec proto/pattern-steps)}]]
   [:len [:int {:min 1 :max 64}]]
   [:pitch [:int {:min 0 :max 127}]]
   [:vel [:and number? [:>= 0] [:<= 1]]]])

(def Notes [:vector {:max proto/pattern-steps} Note])

(defn- one-kind-per-row?
  "A row holds steps or notes, not both."
  [{:keys [steps notes]}]
  (not-any? #(contains? notes %) (keys steps)))

(def Pattern
  [:and
   [:map
    [:steps {:optional true} [:map-of :keyword Steps]]
    [:notes {:optional true} [:map-of :keyword Notes]]]
   [:fn {:error/message "a row holds steps or notes, not both"} one-kind-per-row?]])

(defn rows
  "Every row of a pattern: [row-id {:steps s}] or [row-id {:notes n}]."
  [{:keys [steps notes]}]
  (concat (map (fn [[r s]] [r {:steps s}]) steps)
          (map (fn [[r n]] [r {:notes n}]) notes)))

(def Generator
  "What a channel plays: a built-in drum, or a sample file. Relative sample
  paths are resolved against `gorilla.engine/samples-root`."
  [:or
   [:tuple [:= :builtin] [:map [:voice [:enum :kick :snare :hat :bass :lead]]]]
   [:tuple [:= :sampler] [:map [:sample [:string {:min 1}]]]]])

(def Channel
  [:map [:gen Generator]])

(defn- rows-have-channels?
  "A row plays the channel with the same id, so every row needs one."
  [{:keys [patterns channels]}]
  (every? #(contains? channels %)
          (mapcat (comp #(map first (rows %)) val) patterns)))

(def Clip
  "A pattern placed in the arrangement, in bars. :track is a lane for
  display; it doesn't change what plays."
  [:map {:closed true}
   [:pattern :keyword]
   [:start [:int {:min 0}]]
   [:len [:int {:min 1}]]
   [:track {:optional true} [:int {:min 0}]]])

(defn- clips-have-patterns? [{:keys [playlist patterns]}]
  (every? #(contains? patterns (:pattern %)) playlist))

(def Song
  [:and
   [:map
    [:song/bpm [:and number? [:>= 20] [:<= 999]]]
    [:song/time-sig [:tuple pos-int? pos-int?]]
    [:patterns [:map-of :keyword Pattern]]
    [:channels [:map-of :keyword Channel]]
    [:playlist [:vector {:max proto/max-clips} Clip]]
    ;; With a playlist: start over after the last clip instead of stopping.
    [:song/loop {:optional true} :boolean]
    [:mixer :map]]
   [:fn {:error/message "every row needs a channel with the same id"}
    rows-have-channels?]
   [:fn {:error/message "every clip needs its pattern"}
    clips-have-patterns?]])

(def valid? (m/validator Song))

(def explain (m/explainer Song))

(def empty-song
  {:song/bpm 120
   :song/time-sig [4 4]
   :patterns {}
   :channels {}
   :playlist []
   :mixer {}})

(def default-song
  "Matches what the engine plays before any brain connects."
  (assoc empty-song
         :channels
         {:kick  {:gen [:builtin {:voice :kick}]}
          :snare {:gen [:builtin {:voice :snare}]}
          :hat   {:gen [:builtin {:voice :hat}]}}
         :patterns
         {:drums {:steps {:kick  [1 0 0 0 1 0 0 0 1 0 0 0 1 0 0 0]
                          :snare [0 0 0 0 1 0 0 0 0 0 0 0 1 0 0 0]
                          :hat   [1 0 1 0 1 0 1 0 1 0 1 0 1 0 1 0]}}}))
