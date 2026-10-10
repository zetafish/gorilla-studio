(ns gorilla.song
  "The song model: one immutable EDN value.")

(def empty-song
  {:song/bpm 120
   :song/time-sig [4 4]
   :patterns {}
   :channels {}
   :playlist []
   :mixer {}})
