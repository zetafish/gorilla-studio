(ns gorilla.song
  "The song model: one immutable EDN value."
  (:require [gorilla.proto-schema :as proto]
            [malli.core :as m]))

(def Steps
  [:vector {:min proto/pattern-steps :max proto/pattern-steps} [:enum 0 1]])

(def Pattern
  [:map
   [:steps {:optional true} [:map-of :keyword Steps]]])

(def Song
  [:map
   [:song/bpm [:and number? [:> 0]]]
   [:song/time-sig [:tuple pos-int? pos-int?]]
   [:patterns [:map-of :keyword Pattern]]
   [:channels :map]
   [:playlist [:vector :any]]
   [:mixer :map]])

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
  "Matches the pattern the engine plays before any brain connects."
  (assoc empty-song
         :patterns
         {:drums {:steps {:kick  [1 0 0 0 1 0 0 0 1 0 0 0 1 0 0 0]
                          :snare [0 0 0 0 1 0 0 0 0 0 0 0 1 0 0 0]
                          :hat   [1 0 1 0 1 0 1 0 1 0 1 0 1 0 1 0]}}}))
