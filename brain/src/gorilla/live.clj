(ns gorilla.live
  "Live patterns: rows defined as functions of the bar number.

    (defpattern :hat (fn [bar] (euclid (+ 3 (mod bar 4)) 16)))

  The brain never schedules notes in real time. A scheduler watches the
  engine's playhead and sends each live row's steps for the next
  `lookahead-bars` bars as `:pattern/schedule-bar` patches. The engine
  switches rows exactly on the bar line. Redefining a pattern re-sends the bars
  already queued, so the change lands on the next bar.

  Live output goes straight to the engine, not into the song, so it never
  floods undo history. When a pattern is removed, the engine gets the song's
  own row back."
  (:require [gorilla.engine :as engine]
            [gorilla.pattern :as p]
            [gorilla.studio :as studio]))

(def lookahead-bars 2)

(def ^:private tick-ms 50)

(defonce ^{:doc "{[pattern row] {:f fn, :source string}}"} patterns (atom {}))

;; --- Planning (pure) ----------------------------------------------------------

(defn- render-bar
  "Steps for `bar`, or an error message."
  [f bar]
  (try
    {:steps (p/steps (f bar))}
    (catch Throwable e
      {:error (str (.getSimpleName (class e)) ": " (ex-message e))})))

(defn plan
  "What to send so the engine has bars `from`..`to` (inclusive) of every live
  pattern. `sent` maps [pattern row bar] to steps already sent; only bars that
  are new or changed become ops. Returns {:ops :sent :errors}."
  [patterns sent from to]
  (reduce
   (fn [acc [[pattern row] {:keys [f]}]]
     (reduce
      (fn [acc bar]
        (let [{:keys [steps error]} (render-bar f bar)
              k [pattern row bar]]
          (cond
            error (assoc-in acc [:errors [pattern row]] error)
            (= steps (get sent k)) acc
            :else (-> acc
                      (update :ops conj {:op :pattern/schedule-bar :pattern pattern
                                         :row row :bar bar :steps steps})
                      (assoc-in [:sent k] steps)))))
      acc
      (range from (inc to))))
   {:ops [] :errors {} :sent (into {} (filter (fn [[[_ _ bar] _]] (>= bar from))) sent)}
   (sort-by key patterns)))

;; --- Scheduler ----------------------------------------------------------------

;; {:sent {...} :epoch-key [engine-id transport-count] :bar n :errors {...}}
(defonce ^:private state (atom {:sent {}}))

(defonce ^:private scheduler (atom nil))

;; Ticks come from the scheduler thread and from set-pattern!; one at a time.
(defonce ^:private tick-lock (Object.))

(defn- log [& args] (apply println "gorilla.live:" args))

(defn- report-errors! [old new]
  (doseq [[k msg] new
          :when (not= msg (get old k))]
    (log "pattern" k "failed, keeping its previous bar:" msg)))

(defn- tick-unlocked! []
  (let [{:keys [bar]} @engine/playhead
        identity-key [(engine/engine-id) @engine/transport-starts]]
    (when (and bar (first identity-key))
      (let [{:keys [sent errors] :as st} @state
            ;; A new engine, a restart of playback or a jump back means the
            ;; engine's queue is empty: send everything again.
            sent (if (or (not= identity-key (:identity-key st))
                         (< bar (:bar st 0)))
                   {}
                   sent)
            result (plan @patterns sent (inc bar) (+ bar lookahead-bars))]
        (report-errors! errors (:errors result))
        (when (or (empty? (:ops result))
                  (engine/send-live! (:ops result)))
          (reset! state {:sent (:sent result) :identity-key identity-key
                         :bar bar :errors (:errors result)}))))))

(defn- tick!
  "Sends whatever the engine is missing for the next few bars."
  []
  (locking tick-lock
    (tick-unlocked!)))

(defn- ensure-scheduler! []
  (when-not (some-> ^Thread @scheduler .isAlive)
    (reset! scheduler
            (doto (Thread.
                   (fn []
                     (loop []
                       (try (tick!)
                            (catch Throwable e (log "scheduler error:" (ex-message e))))
                       (Thread/sleep (long tick-ms))
                       (recur))))
              (.setName "gorilla-live-scheduler")
              (.setDaemon true)
              (.start)))))

;; --- API ----------------------------------------------------------------------

(defn set-pattern!
  "Makes row `row` of `pattern` live: `f` takes a bar number and returns
  anything `gorilla.pattern/steps` accepts. Takes effect at the next bar."
  [pattern row f source]
  (when-not (get-in @studio/song [:patterns pattern :steps row])
    (throw (ex-info (str "No row " row " in pattern " pattern) {:pattern pattern :row row})))
  (let [{:keys [error]} (render-bar f 0)]
    (when error
      (throw (ex-info (str "Pattern fails on bar 0: " error) {:pattern pattern :row row}))))
  (swap! patterns assoc [pattern row] {:f f :source source})
  (ensure-scheduler!)
  (tick!)
  [pattern row])

(defmacro defpattern
  "(defpattern :hat (fn [bar] ...)) or (defpattern :drums :hat (fn [bar] ...))."
  ([row f] `(defpattern :drums ~row ~f))
  ([pattern row f] `(set-pattern! ~pattern ~row ~f ~(pr-str f))))

(defn remove-pattern!
  "Stops a live pattern; the engine plays the song's row again from the next
  buffer."
  ([row] (remove-pattern! :drums row))
  ([pattern row]
   (locking tick-lock
     (swap! patterns dissoc [pattern row])
     (swap! state update :sent #(into {} (remove (fn [[[p r _] _]] (= [p r] [pattern row]))) %))
     (when (engine/connected?)
       (engine/send-live! [{:op :pattern/set-steps :pattern pattern :row row
                            :steps (get-in @studio/song [:patterns pattern :steps row])}])))
   [pattern row]))

(defn play!
  "Starts playback with live patterns already in place for the first bars."
  []
  (locking tick-lock
    (let [{:keys [ops sent]} (plan @patterns {} 0 lookahead-bars)]
      (engine/set-playing! true ops)
      (swap! state assoc :sent sent :bar 0
             :identity-key [(engine/engine-id) @engine/transport-starts])
      :playing)))
