(ns gorilla.studio
  "The running song: one atom holding the song value, plus undo history."
  (:require [gorilla.command :as command]
            [gorilla.song :as song]))

(defonce song (atom song/default-song))

(defonce history (atom {:past [] :future []}))

;; State outside the song that belongs with it when the whole song is
;; replaced, such as live patterns. {key {:capture (fn [] v) :restore (fn [v])
;; :phase :before|:after}}: :before restores run before the song changes,
;; :after ones once it has.
(defonce companions (atom {}))

(defn register-companion! [k m]
  (swap! companions assoc k m))

(defn- capture []
  (into {} (for [[k {:keys [capture]}] @companions] [k (capture)])))

(defn- restore! [captured phase]
  (doseq [[k {:keys [restore] p :phase}] @companions
          :when (and (= p phase) (contains? captured k))]
    (restore (get captured k))))

(defn- with-companions [song captured]
  (vary-meta song assoc ::companions captured))

(defn dispatch!
  "Runs a command against the current song. Throws, leaving everything
  untouched, if the command is invalid. Replacing the whole song also records
  its companions (live patterns, ...) so undo brings them back. `before-commit`
  runs after that, just before the new song is set."
  ([cmd] (dispatch! cmd {}))
  ([cmd {:keys [before-commit]}]
   (let [old @song
         new (command/run old cmd)
         entry (if (= :song/replace (:cmd cmd)) (with-companions old (capture)) old)]
     (when (not= old new)
       (swap! history (fn [h] (-> h (update :past conj entry) (assoc :future []))))
       (when before-commit (before-commit))
       (reset! song new))
     new)))

(defn- step! [from to]
  (when-let [target (peek (from @history))]
    (let [captured (::companions (meta target))
          current (cond-> @song captured (with-companions (capture)))]
      (swap! history (fn [h] (-> h (update from pop) (update to conj current))))
      (when captured (restore! captured :before))
      (reset! song (with-meta target nil))
      (when captured (restore! captured :after))
      target)))

(defn undo! [] (step! :past :future))

(defn redo! [] (step! :future :past))

(defn reset-song!
  "Replaces the song and clears history. For tests and the REPL."
  ([] (reset-song! song/default-song))
  ([s]
   (reset! history {:past [] :future []})
   (reset! song s)))
