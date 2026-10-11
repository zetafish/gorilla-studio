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
            [gorilla.studio :as studio]
            [sci.core :as sci])
  (:import (java.util.concurrent Callable ExecutionException Executors ThreadFactory
                                 TimeUnit TimeoutException)))

(def lookahead-bars 2)

(def ^:private tick-ms 50)

(def budget-ms
  "Longest a pattern may take to render one bar. One that takes longer is
  stopped for good (until redefined), so it can't hold up the others."
  100)

(defonce ^{:doc "{[pattern row] {:f fn, :source string, :frozen bars-or-nil}}.
  A frozen pattern replays saved bars instead of running code."}
  patterns (atom {}))

;; --- Running pattern code within a budget -------------------------------------

(defonce ^:private pool
  (Executors/newCachedThreadPool
   (reify ThreadFactory
     (newThread [_ r]
       (doto (Thread. ^Runnable r "gorilla-live-pattern") (.setDaemon true))))))

;; Patterns that ran over budget; never run again until redefined.
(defonce ^:private stuck (atom #{}))

(defn- guarded
  "`f` run on a worker thread with `budget-ms` per call."
  [k f]
  (fn [bar]
    (when (@stuck k)
      (throw (ex-info "stopped after running over budget; redefine it to retry" {})))
    (let [fut (.submit pool ^Callable (fn [] (f bar)))]
      (try
        (.get fut budget-ms TimeUnit/MILLISECONDS)
        (catch TimeoutException _
          ;; Interrupts the thread; pure code that never checks may keep it
          ;; busy until it finishes.
          (.cancel fut true)
          (swap! stuck conj k)
          (throw (ex-info (str "took longer than " budget-ms " ms on bar " bar) {})))
        (catch ExecutionException e
          (throw (or (.getCause e) e)))))))

;; --- Planning (pure) ----------------------------------------------------------

(defn- row-exists? [pattern row]
  (let [p (get-in @studio/song [:patterns pattern])]
    (boolean (or (get-in p [:steps row]) (get-in p [:notes row])))))

(defn- render-bar
  "What `f` plays in `bar`: {:steps [...]} or {:notes [...]}, or {:error msg}."
  [f bar]
  (try
    (let [x (f bar)]
      (if (p/notes? x)
        {:notes (p/notes x)}
        {:steps (p/steps x)}))
    (catch Throwable e
      {:error (str (.getSimpleName (class e)) ": " (ex-message e))})))

(defn- bar-data
  "A rendered bar as saved: steps vector or notes vector."
  [{:keys [steps notes]}]
  (or notes steps))

(defn plan
  "What to send so the engine has bars `from`..`to` (inclusive) of every live
  pattern. `sent` maps [pattern row bar] to steps already sent; only bars that
  are new or changed become ops. Returns {:ops :sent :errors}."
  [patterns sent from to]
  (reduce
   (fn [acc [[pattern row] {:keys [f]}]]
     (reduce
      (fn [acc bar]
        (let [{:keys [steps notes error] :as rendered} (render-bar f bar)
              k [pattern row bar]
              data (bar-data rendered)]
          (cond
            ;; The first error says what went wrong; later ones may only
            ;; say the pattern was stopped.
            error (update-in acc [:errors [pattern row]] #(or % error))
            (= data (get sent k)) acc
            :else (-> acc
                      (update :ops conj
                              (if notes
                                {:op :pattern/schedule-notes :pattern pattern :row row :bar bar
                                 :notes notes}
                                {:op :pattern/schedule-bar :pattern pattern :row row :bar bar
                                 :steps steps}))
                      (assoc-in [:sent k] data)))))
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
            ;; A live row removed from the song has nowhere to play.
            live (into {}
                       (comp (filter (fn [[[pattern row]]] (row-exists? pattern row)))
                             (map (fn [[k entry]] [k (update entry :f #(guarded k %))])))
                       @patterns)
            result (plan live sent (inc bar) (+ bar lookahead-bars))]
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

(defn- install! [pattern row entry]
  (when-not (row-exists? pattern row)
    (throw (ex-info (str "No row " row " in pattern " pattern) {:pattern pattern :row row})))
  (swap! stuck disj [pattern row])
  (let [{:keys [error]} (render-bar (guarded [pattern row] (:f entry)) 0)]
    (when error
      (throw (ex-info (str "Pattern fails on bar 0: " error) {:pattern pattern :row row}))))
  (swap! patterns assoc [pattern row] entry)
  (ensure-scheduler!)
  (tick!)
  [pattern row])

(defn set-pattern!
  "Makes row `row` of `pattern` live: `f` takes a bar number and returns
  anything `gorilla.pattern/steps` accepts. Takes effect at the next bar."
  [pattern row f source]
  (install! pattern row {:f f :source source :frozen nil}))

(defn loop-start
  "Where saved `bars` start repeating: the first bar s, in the first half,
  such that bars s..end are at least two repeats of one period. 0 when there
  is none, so all the bars loop. An 8-bar intro followed by a repeating groove
  gives 8; a couple of equal bars at the very end don't count."
  [bars]
  (let [n (count bars)
        periodic? (fn [s p]
                    (every? #(= (nth bars %) (nth bars (+ % p))) (range s (- n p))))]
    (or (first (for [s (range 0 (inc (quot n 2)))
                     p (range 1 (inc (quot (- n s) 2)))
                     :when (and (zero? (mod (- n s) p)) (periodic? s p))]
                 s))
        0)))

(defn- replay [bars loop-from]
  (let [n (count bars)
        period (- n loop-from)]
    (fn [bar]
      (if (< bar n)
        (nth bars bar)
        (nth bars (+ loop-from (mod (- bar loop-from) period)))))))

(defn set-frozen!
  "Makes a row live from saved `bars`: they play once, then the bars from
  `loop-from` repeat. `source` is kept as text and not run; `activate!` runs
  it."
  ([pattern row source bars] (set-frozen! pattern row source bars 0))
  ([pattern row source bars loop-from]
   (let [bars (vec bars)
         loop-from (if (< -1 loop-from (count bars)) loop-from 0)]
     (install! pattern row {:f (replay bars loop-from) :source source
                            :frozen bars :loop-from loop-from}))))

(defn materialize
  "Every live pattern as data: its source and its first `n` bars (a frozen
  pattern keeps the bars it has). For saving."
  [n]
  (into (sorted-map)
        (map (fn [[k {:keys [f source frozen loop-from]}]]
               (let [bars (or frozen
                              (mapv #(or (bar-data (render-bar (guarded k f) %))
                                         (p/steps []))
                                    (range n)))]
                 [k {:source source
                     :bars bars
                     :loop-from (if frozen (or loop-from 0) (loop-start bars))}])))
        @patterns))

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
     (when (and (engine/connected?) (row-exists? pattern row))
       (let [p (get-in @studio/song [:patterns pattern])]
         (engine/send-live!
          [(if-let [notes (get-in p [:notes row])]
             {:op :pattern/set-notes :pattern pattern :row row
              :notes (mapv #(update % :vel double) notes)}
             {:op :pattern/set-steps :pattern pattern :row row
              :steps (get-in p [:steps row])})]))))
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

(defn clear!
  "Removes every live pattern."
  []
  (doseq [[pattern row] (keys @patterns)]
    (remove-pattern! pattern row)))

;; --- Running saved source -------------------------------------------------------

(def sandbox-fns
  "What saved pattern source can call, besides pure clojure.core."
  {'steps p/steps 'euclid p/euclid 'rotate p/rotate 'invert p/invert
   'overlay p/overlay 'mask p/mask 'chance p/chance 'every p/every
   'note p/note 'scale p/scale 'degree p/degree 'chord p/chord
   'markov-walk p/markov-walk 'rhythm p/rhythm 'transpose p/transpose 'notes p/notes})

(def ^:private denied
  "SCI already leaves out I/O and Java interop; these would let source reach
  further, or run off on other threads."
  '[eval load-string read-string require use import in-ns ns resolve intern
    alter-var-root future future-call pmap pcalls agent send send-off
    add-watch set-validator!])

(defn compile-source
  "Evaluates pattern `source` in a fresh SCI sandbox: pure clojure.core plus
  `sandbox-fns`, no I/O, no Java. Returns the pattern function."
  [source]
  (let [ctx (sci/init {:namespaces {'user sandbox-fns} :deny denied})
        f (sci/eval-string* ctx source)]
    (when-not (fn? f)
      (throw (ex-info "Source doesn't evaluate to a function" {:source source})))
    f))

(defn activate!
  "Runs the saved source of every frozen live pattern, in the sandbox, so it
  generates again instead of replaying saved bars. A pattern whose source
  fails stays frozen. Returns {:active [...] :failed {[pattern row] reason}}."
  []
  (reduce (fn [acc [[pattern row] {:keys [source frozen]}]]
            (if-not frozen
              acc
              (try
                (set-pattern! pattern row (compile-source source) source)
                (update acc :active conj [pattern row])
                (catch Exception e
                  (assoc-in acc [:failed [pattern row]] (ex-message e))))))
          {:active [] :failed {}}
          (sort-by key @patterns)))

;; Undo of a whole-song replace (new-song!, open!) brings live patterns back.
(studio/register-companion!
 ::patterns
 {:phase :after
  :capture (fn [] @patterns)
  :restore (fn [saved]
             (clear!)
             (doseq [[[pattern row] entry] saved]
               (try (install! pattern row entry)
                    (catch Exception e
                      (log "not restoring" [pattern row] "-" (ex-message e))))))})
