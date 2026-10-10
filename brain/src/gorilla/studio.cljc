(ns gorilla.studio
  "The running song: one atom holding the song value, plus undo history."
  (:require [gorilla.command :as command]
            [gorilla.song :as song]))

(defonce song (atom song/default-song))

(defonce history (atom {:past [] :future []}))

(defn dispatch!
  "Runs a command against the current song. Throws, leaving everything
  untouched, if the command is invalid."
  [cmd]
  (let [old @song
        new (command/run old cmd)]
    (when (not= old new)
      (swap! history (fn [h] (-> h (update :past conj old) (assoc :future []))))
      (reset! song new))
    new))

(defn undo! []
  (when-let [prev (peek (:past @history))]
    (swap! history (fn [h] (-> h (update :past pop) (update :future conj @song))))
    (reset! song prev)))

(defn redo! []
  (when-let [next (peek (:future @history))]
    (swap! history (fn [h] (-> h (update :future pop) (update :past conj @song))))
    (reset! song next)))

(defn reset-song!
  "Replaces the song and clears history. For tests and the REPL."
  ([] (reset-song! song/default-song))
  ([s]
   (reset! history {:past [] :future []})
   (reset! song s)))
