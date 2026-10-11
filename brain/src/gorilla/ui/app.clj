(ns gorilla.ui.app
  "The desktop UI (HumbleUI). It reads the song and engine state and
  dispatches commands; it is never the source of truth. What to show and what
  a click means come from `gorilla.ui.model`; this namespace only draws and
  wires events.

    (gorilla.ui.app/show!)   ; open the window from a REPL"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [gorilla.engine :as engine]
            [gorilla.file :as file]
            [gorilla.live :as live]
            [gorilla.studio :as studio]
            [gorilla.ui.actions :as actions]
            [gorilla.ui.model :as model]
            [io.github.humbleui.app :as app]
            [io.github.humbleui.canvas :as canvas]
            [io.github.humbleui.ui :as ui]
            [io.github.humbleui.util :as util]
            [io.github.humbleui.window :as window])
  (:import (io.github.humbleui.skija Canvas)))

;; --- State -------------------------------------------------------------------------
;;
;; Signals mirror the brain's atoms so components re-render when they change.
;; The playhead changes 60 times a second, so canvases read it while painting
;; instead, and a watch just asks for a new frame.

(defonce *song (ui/signal @studio/song))
(defonce *live (ui/signal #{}))
(defonce *connected (ui/signal false))
(defonce *view (ui/signal {:pattern :drums :picker nil :status nil}))

(defonce *window (atom nil))

(defn- request-frame []
  (when-some [w @*window]
    (when-not (window/closed? w)
      (window/request-frame w))))

(defn sync-signals!
  "Copies brain state into the signals. Runs on the UI thread when there is a
  window."
  []
  (let [song @studio/song
        pattern (:pattern @*view)]
    (reset! *song song)
    (reset! *live (set (keys @live/patterns)))
    (reset! *connected (engine/connected?))
    ;; Keep a pattern selected that still exists.
    (when-not (contains? (:patterns song) pattern)
      (swap! *view assoc :pattern (first (sort (keys (:patterns song))))))))

(defn- on-ui-thread [f]
  (if @*window
    (app/doui-async (f) (request-frame))
    (f)))

(defn- watch! []
  (add-watch studio/song ::ui (fn [_ _ _ _] (on-ui-thread sync-signals!)))
  (add-watch live/patterns ::ui (fn [_ _ _ _] (on-ui-thread sync-signals!)))
  (add-watch engine/playhead ::ui
             (fn [_ _ old new]
               (when (not= (some? old) (some? new)) (on-ui-thread sync-signals!))
               (request-frame))))

;; --- Actions -------------------------------------------------------------------------

(defn- status! [msg]
  (swap! *view assoc :status msg))

(defn- run! [cmd] (status! (actions/run! cmd)))

(defn toggle-play! [] (status! (actions/toggle-play!)))
(defn undo! [] (status! (actions/undo!)))
(defn redo! [] (status! (actions/redo!)))
(defn save! [] (status! (actions/save!)))

(defn open! [name]
  (status! (actions/open! name))
  (swap! *view assoc :picker nil))

(defn add-channel! [gen base-name]
  (status! (actions/add-channel! gen base-name))
  (swap! *view assoc :picker nil))

;; --- Look ----------------------------------------------------------------------------

(def colors
  {:bg 0xFF1E2128 :panel 0xFF262A33 :cell 0xFF333844 :cell-beat 0xFF3B4150
   :cell-empty 0xFF2B2F38 :on 0xFFE8873A :note 0xFF5AA6E8 :live 0xFF8BD17C
   :text 0xFFE6E8EE :muted 0xFF8A91A0 :playhead 0x40FFFFFF :clip 0xFF4F7FD0
   :clip-active 0xFF6E9BEA :line 0xFF3A3F4B})

(defn- fill [ctx k] (ui/paint {:fill (colors k)} ctx))

(defn- text [^Canvas canvas ctx s x y {:keys [size color] :or {size 13 color :text}}]
  (with-open [p (fill ctx color)]
    (canvas/draw-string canvas s x y (ui/get-font {:font-size size} ctx) p)))

(defn- note-name [pitch]
  (str (nth ["C" "C#" "D" "D#" "E" "F" "F#" "G" "G#" "A" "A#" "B"] (mod pitch 12))
       (dec (quot pitch 12))))

;; --- Rack ------------------------------------------------------------------------------

(defn- current-step
  "The step playing now in `pattern`, or nil if the pattern isn't playing."
  [song pattern]
  (let [{:keys [bar step playing]} @engine/playhead]
    (when (and playing bar
               (contains? (model/active-patterns song (model/song-position song bar)) pattern))
      step)))

(defn- paint-rack [song pattern live ctx ^Canvas canvas size]
  (let [s (:scale ctx)
        {:keys [row-h name-w]} model/layout
        rows (model/rack song pattern live (actions/live-bars))
        width (/ (:width size) s)
        cw (model/cell-w width)
        step-now (current-step song pattern)]
    (doseq [[i {:keys [channel gen-label kind steps notes live? played]}] (map-indexed vector rows)
            :let [y (* i row-h)]]
      ;; name column
      (with-open [p (fill ctx :panel)]
        (canvas/draw-rect canvas (util/rect-xywh 0 (* s (+ y 1)) (* s (- name-w 4)) (* s (- row-h 2))) p))
      (text canvas ctx (name channel) (* s 8) (* s (+ y 14)) {})
      (text canvas ctx gen-label (* s 8) (* s (+ y 26)) {:size 10 :color :muted})
      (when live? (text canvas ctx "live" (* s (- name-w 54)) (* s (+ y 18)) {:size 10 :color :live}))
      (text canvas ctx "×" (* s (- name-w 18)) (* s (+ y 19)) {:color :muted})
      ;; cells
      (doseq [step (range model/steps)
              :let [x (+ name-w (* step cw))
                    on? (and (= kind :steps) (= 1 (get steps step)))
                    k (cond on? :on
                            (= kind :none) :cell-empty
                            (zero? (mod (quot step 4) 2)) :cell
                            :else :cell-beat)]]
        (with-open [p (fill ctx k)]
          (canvas/draw-rect canvas (util/rect-xywh (* s (+ x 1)) (* s (+ y 1)) (* s (- cw 2)) (* s (- row-h 2))) p)))
      ;; notes, as blocks spanning their length
      (when (= kind :notes)
        (doseq [{:keys [t len pitch]} notes
                :let [x (+ name-w (* t cw))
                      w (* cw (min len (- model/steps t)))]]
          (with-open [p (fill ctx :note)]
            (canvas/draw-rect canvas (util/rect-xywh (* s (+ x 1)) (* s (+ y 3)) (* s (- w 2)) (* s (- row-h 6))) p))
          (text canvas ctx (note-name pitch) (* s (+ x 4)) (* s (+ y 19)) {:size 10 :color :bg})))
      ;; what a live pattern plays this bar: a thin line under each hit
      (when (and live? played)
        (doseq [t (if (map? (first played)) (map :t played) (keep-indexed #(when (= 1 %2) %1) played))
                :let [x (+ name-w (* t cw))]]
          (with-open [p (fill ctx :live)]
            (canvas/draw-rect canvas (util/rect-xywh (* s (+ x 3)) (* s (+ y row-h -5)) (* s (- cw 6)) (* s 3)) p)))))
    ;; playhead column
    (when step-now
      (with-open [p (fill ctx :playhead)]
        (canvas/draw-rect canvas (util/rect-xywh (* s (+ name-w (* step-now cw))) 0 (* s cw) (* s row-h (count rows))) p)))))

(defn- click? [e]
  (and (= :mouse-button (:event e)) (:pressed? e) (= :primary (:button e))))

(defn- on-rack-event [song pattern ctx e]
  (when (click? e)
    (let [s (:scale ctx)
          width (/ (:width (:bounds ui/*node*)) s)
          x (/ (:x e) s)
          y (/ (:y e) s)
          rows (model/rack song pattern #{} {})
          row (int (quot y (:row-h model/layout)))]
      (cond
        ;; the × at the end of a channel's name removes the channel
        (and (< -1 row (count rows)) (<= (- (:name-w model/layout) 24) x (:name-w model/layout)))
        (run! {:cmd :channel/remove :channel (:channel (nth rows row))})

        :else
        (when-some [{:keys [row step]} (model/rack-hit width x y)]
          (when-some [r (get rows row)]
            (run! (model/click-step pattern r step)))))
      true)))

;; --- Playlist ----------------------------------------------------------------------------

(def ^:private ruler-h "Bar numbers above the lanes." 16)

(defn- paint-playlist [song ctx ^Canvas canvas size]
  (canvas/translate canvas 0 (* (:scale ctx) ruler-h))
  (let [s (:scale ctx)
        {:keys [lane-h lane-name-w]} model/layout
        lanes (model/lanes song)
        bars (model/visible-bars song)
        width (/ (:width size) s)
        bar-w (/ (- width lane-name-w) bars)
        {:keys [bar step playing]} @engine/playhead
        pos (model/song-position song bar)
        height (* lane-h (max 1 (count lanes)))]
    ;; bar lines, stronger every 4
    (doseq [b (range (inc bars))
            :let [x (+ lane-name-w (* b bar-w))]]
      (with-open [p (ui/paint {:stroke (if (zero? (mod b 4)) 0xFF4A505E 0xFF30343E) :width s} ctx)]
        (canvas/draw-line canvas (* s x) 0 (* s x) (* s height) p)))
    (doseq [[i {:keys [pattern clips]}] (map-indexed vector lanes)
            :let [y (* i lane-h)]]
      (text canvas ctx (name pattern) (* s 6) (* s (+ y 17)) {})
      (doseq [{:keys [start len]} clips
              :let [x (+ lane-name-w (* start bar-w))
                    active? (and pos (<= start pos (dec (+ start len))))]]
        (with-open [p (fill ctx (if active? :clip-active :clip))]
          (canvas/draw-rect canvas (util/rect-xywh (* s (+ x 1)) (* s (+ y 4)) (* s (- (* len bar-w) 2)) (* s (- lane-h 8))) p))))
    ;; bar numbers in the ruler
    (doseq [b (range 0 bars 4)
            :let [x (+ lane-name-w (* b bar-w))]]
      (text canvas ctx (str b) (* s (+ x 3)) (* s -4) {:size 10 :color :muted}))
    (when (and playing pos)
      (let [x (+ lane-name-w (* bar-w (+ pos (/ (or step 0) model/steps))))]
        (with-open [p (ui/paint {:stroke 0xFFFFFFFF :width (* 2 s)} ctx)]
          (canvas/draw-line canvas (* s x) 0 (* s x) (* s height) p))))))

(defn- on-playlist-event [song ctx e]
  (when (click? e)
    (let [s (:scale ctx)
          width (/ (:width (:bounds ui/*node*)) s)
          lanes (model/lanes song)]
      (when-some [{:keys [lane bar]} (model/playlist-hit width (model/visible-bars song)
                                                         (/ (:x e) s) (- (/ (:y e) s) ruler-h))]
        (when-some [l (get lanes lane)]
          (run! (model/click-playlist l bar))))
      true)))

;; --- Components ---------------------------------------------------------------------------

(defn- paint-position [ctx ^Canvas canvas _size]
  (let [song @*song
        {:keys [bar step playing]} @engine/playhead
        pos (model/song-position song bar)
        s (:scale ctx)]
    (text canvas ctx
          (cond
            (nil? bar) "—"
            pos (format "bar %d · %d   (song %d/%d)" bar (inc step) (inc pos) (model/song-bars song))
            :else (format "bar %d · %d%s" bar (inc step) (if playing "" "  stopped")))
          0 (* s 18) {})))

(ui/defcomp label [& args]
  [ui/label {:paint {:fill (colors :text)}} (apply str args)])

(ui/defcomp muted [& args]
  [ui/label {:paint {:fill (colors :muted)} :font-size 11} (apply str args)])

(ui/defcomp top-bar []
  (let [song @*song
        connected @*connected]
    [ui/row {:gap 8}
     [ui/button {:on-click (fn [_] (toggle-play!))} "Play / Stop"]
     [ui/button {:on-click (fn [_] (run! {:cmd :song/set-bpm :bpm (max 20 (dec (:song/bpm song)))}))} "−"]
     [ui/align {:y :center} [label (:song/bpm song) " BPM"]]
     [ui/button {:on-click (fn [_] (run! {:cmd :song/set-bpm :bpm (min 999 (inc (:song/bpm song)))}))} "+"]
     [ui/size {:width 260 :height 28} [ui/canvas {:on-paint paint-position}]]
     [ui/align {:y :center} [ui/label {:paint {:fill (if connected (colors :live) (colors :on))}}
                             (if connected "● engine" "○ no engine")]]
     ^{:stretch 1} [ui/gap]
     [ui/button {:on-click (fn [_] (undo!))} "Undo"]
     [ui/button {:on-click (fn [_] (redo!))} "Redo"]
     [ui/button {:on-click (fn [_] (save!))} "Save"]
     [ui/button {:on-click (fn [_] (swap! *view update :picker #(when-not (= % :open) :open)))} "Open…"]]))

(ui/defcomp pattern-list []
  (let [song @*song
        selected (:pattern @*view)]
    [ui/column {:gap 4}
     [muted "PATTERNS"]
     (for [p (sort (keys (:patterns song)))]
       [ui/clickable {:on-click (fn [_] (swap! *view assoc :pattern p))}
        [ui/rect {:paint {:fill (if (= p selected) (colors :clip) (colors :panel))} :radius 4}
         [ui/padding {:horizontal 8 :vertical 6}
          [ui/row
           ^{:stretch 1} [label (name p)]
           [ui/clickable {:on-click (fn [_] (run! {:cmd :pattern/remove :pattern p}))}
            [ui/label {:paint {:fill (colors :muted)}} "×"]]]]]])
     [ui/button {:on-click (fn [_]
                             (let [[p status] (actions/add-pattern!)]
                               (status! status)
                               (swap! *view assoc :pattern p)))}
      "+ Pattern"]]))

(ui/defcomp rack-canvas []
  (let [song @*song
        live @*live
        pattern (:pattern @*view)]
    [ui/size {:height (* (:row-h model/layout) (max 1 (count (:channels song))))}
     [ui/canvas {:on-paint (fn [ctx canvas size] (paint-rack song pattern live ctx canvas size))
                 :on-event (fn [ctx e] (on-rack-event song pattern ctx e))}]]))

(ui/defcomp rack-view []
  (let [pattern (:pattern @*view)]
    [ui/column {:gap 6}
     [muted "CHANNELS · " (if pattern (name pattern) "no pattern")]
     (if pattern
       [rack-canvas]
       [muted "Add a pattern to start."])
     [ui/row {:gap 6}
      [ui/button {:on-click (fn [_] (swap! *view update :picker #(when-not (= % :channel) :channel)))}
       "+ Channel"]]]))

(ui/defcomp picker []
  (let [{:keys [picker]} @*view]
    (case picker
      :channel
      [ui/column {:gap 6}
       [muted "BUILT-IN"]
       [ui/row {:gap 6}
        (for [b actions/builtins]
          [ui/button {:on-click (fn [_] (add-channel! [:builtin {:voice b}] (name b)))} (name b)])]
       [muted "SAMPLES (macOS sounds and brain/samples/)"]
       (for [row (partition-all 8 (actions/sample-files))]
         [ui/row {:gap 6}
          (for [f row
                :let [n (str/replace (.getName (io/file f)) #"\.[^.]+$" "")]]
            [ui/button {:on-click (fn [_] (add-channel! [:sampler {:sample f}] (str/lower-case n)))} n])])]

      :open
      [ui/column {:gap 6}
       [muted "SONGS IN brain/" actions/songs-dir "/"]
       (let [folders (actions/song-folders)]
         (if (seq folders)
           [ui/row {:gap 6}
            (for [n folders] [ui/button {:on-click (fn [_] (open! n))} n])]
           [muted "None yet. Save one first."]))]

      [ui/gap])))

(ui/defcomp playlist-canvas []
  (let [song @*song]
    [ui/size {:height (+ ruler-h (* (:lane-h model/layout) (max 1 (count (:patterns song)))))}
     [ui/canvas {:on-paint (fn [ctx canvas size] (paint-playlist song ctx canvas size))
                 :on-event (fn [ctx e] (on-playlist-event song ctx e))}]]))

(ui/defcomp playlist-view []
  (let [song @*song]
    [ui/column {:gap 6}
     [ui/row {:gap 8}
      [ui/align {:y :center} [muted "PLAYLIST · click a bar to add a clip, a clip to remove it"]]
      ^{:stretch 1} [ui/gap]
      [ui/button {:on-click (fn [_] (run! {:cmd :song/set-loop :loop (not (:song/loop song))}))}
       (if (:song/loop song) "Loop: on" "Loop: off")]
      [ui/button {:on-click (fn [_] (run! {:cmd :playlist/set :clips []}))} "Clear"]]
     [playlist-canvas]]))

(ui/defcomp status-line []
  (let [{:keys [status]} @*view
        current @file/current]
    [ui/row {:gap 12}
     [muted (if current (str "Song: " current) "Unsaved song")]
     ^{:stretch 1} [ui/gap]
     (if status [ui/label {:paint {:fill (colors :on)}} status] [ui/gap])]))

(ui/defcomp root []
  [ui/rect {:paint {:fill (colors :bg)}}
   [ui/padding {:padding 14}
    [ui/column {:gap 14}
     [top-bar]
     [ui/row {:gap 14}
      [ui/size {:width 150} [pattern-list]]
      ^{:stretch 1} [ui/column {:gap 14}
                     [rack-view]
                     [picker]]]
     [playlist-view]
     ^{:stretch 1} [ui/gap]
     [status-line]]]])

;; --- Window --------------------------------------------------------------------------------

(defn- on-key [_window e]
  (when (and (= :key (:event e)) (:pressed? e))
    (let [{:keys [key modifiers]} e
          cmd? (or (contains? modifiers :mac-command) (contains? modifiers :control))]
      (cond
        (= :space key) (toggle-play!)
        (and cmd? (= :z key) (contains? modifiers :shift)) (redo!)
        (and cmd? (= :z key)) (undo!)
        (and cmd? (= :s key)) (save!)))))

(defn show!
  "Opens the window. `exit-on-close?` ends the JVM when it closes (for the
  standalone app); from a REPL leave it false."
  ([] (show! {}))
  ([{:keys [exit-on-close?] :or {exit-on-close? false}}]
   (sync-signals!)
   (watch!)
   (ui/start-app!
    (reset! *window
            (ui/window {:title "Gorilla Studio"
                        :width 1180 :height 760
                        :bg-color (colors :bg)
                        :exit-on-close? exit-on-close?
                        :on-event on-key}
                       #'root)))
   :shown))
