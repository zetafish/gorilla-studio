(ns gorilla.song
  "The song model: one immutable EDN value."
  (:require [gorilla.proto-schema :as proto]
            [malli.core :as m]))

(def Steps
  [:vector {:min proto/pattern-steps :max proto/pattern-steps} [:enum 0 1]])

(def Pattern
  [:map
   [:steps {:optional true} [:map-of :keyword Steps]]])

(def Generator
  "What a channel plays: a built-in drum, or a sample file. Relative sample
  paths are resolved against `gorilla.engine/samples-root`."
  [:or
   [:tuple [:= :builtin] [:map [:voice [:enum :kick :snare :hat]]]]
   [:tuple [:= :sampler] [:map [:sample [:string {:min 1}]]]]])

(def Channel
  [:map [:gen Generator]])

(defn- rows-have-channels?
  "A row plays the channel with the same id, so every row needs one."
  [{:keys [patterns channels]}]
  (every? #(contains? channels %)
          (mapcat (comp keys :steps val) patterns)))

(def Song
  [:and
   [:map
    [:song/bpm [:and number? [:>= 20] [:<= 999]]]
    [:song/time-sig [:tuple pos-int? pos-int?]]
    [:patterns [:map-of :keyword Pattern]]
    [:channels [:map-of :keyword Channel]]
    [:playlist [:vector :any]]
    [:mixer :map]]
   [:fn {:error/message "every row needs a channel with the same id"}
    rows-have-channels?]])

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
