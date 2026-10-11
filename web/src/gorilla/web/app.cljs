(ns gorilla.web.app
  "The web UI: a client of the brain's WebSocket (gorilla.server). It shows
  what the brain sends and sends back commands; the song lives in the brain.
  What a click means comes from gorilla.ui.model, shared with the desktop UI.

  Runs in the Tauri app, or in a browser at http://127.0.0.1:7880."
  (:require [cljs.reader :as reader]
            [clojure.string :as str]
            [gorilla.ui.model :as model]
            [re-frame.core :as rf]
            [reagent.dom.client :as rdc]))

;; --- Link to the brain --------------------------------------------------------------

(defn- brain-url
  "The brain's WebSocket: ?brain=host:port, the page's own host when the
  brain serves it, else the default."
  []
  (let [params (js/URLSearchParams. (.. js/window -location -search))
        host (.. js/window -location -host)
        served-by-brain? (str/starts-with? (.. js/window -location -protocol) "http")]
    (str "ws://" (or (.get params "brain")
                     (when (and served-by-brain? (not (str/ends-with? host ":8280"))) host)
                     "127.0.0.1:7880")
         "/ws")))

(defonce ^:private socket (atom nil))

(defn- connect! []
  (let [ws (js/WebSocket. (brain-url))]
    (set! (.-onopen ws) #(rf/dispatch [:ws/open]))
    (set! (.-onmessage ws) #(rf/dispatch [:ws/message (reader/read-string (.-data %))]))
    (set! (.-onclose ws) (fn [_]
                           (rf/dispatch [:ws/closed])
                           (js/setTimeout connect! 1000)))
    (reset! socket ws)))

(rf/reg-fx :ws/send
           (fn [msg]
             (when-let [ws @socket]
               (when (= 1 (.-readyState ws))
                 (.send ws (pr-str msg))))))

;; --- Events -------------------------------------------------------------------------

(rf/reg-event-db :init
                 (fn [_ _]
                   {:view {:pattern nil :picker nil} :brain? false}))

(rf/reg-event-db :ws/open (fn [db _] (assoc db :brain? true :status nil)))

(rf/reg-event-db :ws/closed (fn [db _] (assoc db :brain? false)))

(defn- keep-pattern-selected [db]
  (let [patterns (get-in db [:song :patterns])]
    (if (contains? patterns (get-in db [:view :pattern]))
      db
      (assoc-in db [:view :pattern] (first (sort (keys patterns)))))))

(rf/reg-event-db :ws/message
                 (fn [db [_ {:keys [type] :as msg}]]
                   (case type
                     :state (keep-pattern-selected (merge db (dissoc msg :type)))
                     :playhead (merge db (dissoc msg :type))
                     :status (assoc db :status (:status msg))
                     db)))

(rf/reg-event-fx :command
                 (fn [_ [_ cmd]]
                   {:ws/send {:type :command :cmd cmd}}))

(rf/reg-event-fx :action
                 (fn [{:keys [db]} [_ action & args]]
                   {:db (cond-> db (#{:open :add-channel} action) (assoc-in [:view :picker] nil))
                    :ws/send {:type :action :action action :args (vec args)}}))

(rf/reg-event-db :view
                 (fn [db [_ k v]] (assoc-in db [:view k] v)))

(rf/reg-event-db :toggle-picker
                 (fn [db [_ picker]]
                   (update-in db [:view :picker] #(when-not (= % picker) picker))))

(rf/reg-event-fx :add-pattern
                 (fn [{:keys [db]} _]
                   (let [p (model/next-name "pattern" (get-in db [:song :patterns]))]
                     {:db (assoc-in db [:view :pattern] p)
                      :ws/send {:type :command :cmd {:cmd :pattern/add :pattern p}}})))

;; --- Subscriptions ------------------------------------------------------------------

(rf/reg-sub :db (fn [db _] db))
(rf/reg-sub :song (fn [db _] (:song db)))
(rf/reg-sub :view (fn [db _] (:view db)))
(rf/reg-sub :playhead (fn [db _] (:playhead db)))

(rf/reg-sub :position
            :<- [:song] :<- [:playhead]
            (fn [[song {:keys [bar]}] _]
              (model/song-position song bar)))

(rf/reg-sub :playing-step
  ;; The step playing now in `pattern`, or nil when the pattern isn't playing.
            :<- [:song] :<- [:playhead] :<- [:position]
            (fn [[song {:keys [step playing]} pos] [_ pattern]]
              (when (and playing (contains? (model/active-patterns song pos) pattern))
                step)))

;; --- Views --------------------------------------------------------------------------

(defn- note-name [pitch]
  (str (nth ["C" "C#" "D" "D#" "E" "F" "F#" "G" "G#" "A" "A#" "B"] (mod pitch 12))
       (dec (quot pitch 12))))

(defn top-bar []
  (let [{:keys [song connected? can-undo? can-redo? brain?]} @(rf/subscribe [:db])
        {:keys [bar step playing]} @(rf/subscribe [:playhead])
        pos @(rf/subscribe [:position])]
    [:div.row.top
     [:button {:on-click #(rf/dispatch [:action :toggle-play])} (if playing "■ Stop" "▶ Play")]
     [:button {:on-click #(rf/dispatch [:command {:cmd :song/set-bpm :bpm (max 20 (dec (:song/bpm song)))}])} "−"]
     [:span (:song/bpm song) " BPM"]
     [:button {:on-click #(rf/dispatch [:command {:cmd :song/set-bpm :bpm (min 999 (inc (:song/bpm song)))}])} "+"]
     [:span.position
      (cond (nil? bar) "—"
            pos (str "bar " bar " · " (inc step) "   (song " (inc pos) "/" (model/song-bars song) ")")
            :else (str "bar " bar " · " (inc step) (when-not playing "  stopped")))]
     (cond (not brain?) [:span.warn "○ no brain"]
           connected? [:span.ok "● engine"]
           :else [:span.warn "○ no engine"])
     [:span.grow]
     [:button {:disabled (not can-undo?) :on-click #(rf/dispatch [:action :undo])} "Undo"]
     [:button {:disabled (not can-redo?) :on-click #(rf/dispatch [:action :redo])} "Redo"]
     [:button {:on-click #(rf/dispatch [:action :save])} "Save"]
     [:button {:on-click #(rf/dispatch [:toggle-picker :open])} "Open…"]]))

(defn pattern-list []
  (let [song @(rf/subscribe [:song])
        selected (:pattern @(rf/subscribe [:view]))]
    [:div.patterns
     [:div.muted "PATTERNS"]
     (for [p (sort (keys (:patterns song)))]
       ^{:key p}
       [:div.pattern {:class (when (= p selected) "selected")
                      :on-click #(rf/dispatch [:view :pattern p])}
        [:span (name p)]
        [:span.x {:on-click (fn [e]
                              (.stopPropagation e)
                              (rf/dispatch [:command {:cmd :pattern/remove :pattern p}]))}
         "×"]])
     [:button {:on-click #(rf/dispatch [:add-pattern])} "+ Pattern"]]))

(defn- played-steps [played]
  (set (if (map? (first played))
         (map :t played)
         (keep-indexed #(when (= 1 %2) %1) played))))

(defn rack-row [pattern {:keys [channel gen-label kind steps notes live? played] :as row} playing-step]
  (let [played (played-steps played)]
    [:div.rack-row
     [:div.rack-name
      [:div (name channel)]
      [:div.gen gen-label]
      (when live? [:span.live "live"])
      [:span.x {:on-click #(rf/dispatch [:command {:cmd :channel/remove :channel channel}])} "×"]]
     [:div.cells
      (for [step (range model/steps)]
        ^{:key step}
        [:div.cell {:class [(cond (and (= kind :steps) (= 1 (get steps step))) "on"
                                  (= kind :none) "empty"
                                  (odd? (quot step 4)) "alt")
                            (when (= step playing-step) "playing")]
                    :on-click #(rf/dispatch [:command (model/click-step pattern row step)])}
         (when (and live? (played step)) [:div.played])])
      (when (= kind :notes)
        (for [{:keys [t len pitch]} notes]
          ^{:key t}
          [:div.note {:style {:left (str (* 100 (/ t model/steps)) "%")
                              :width (str (* 100 (/ (min len (- model/steps t)) model/steps)) "%")}}
           (note-name pitch)]))]]))

(defn rack []
  (let [{:keys [song live live-bars]} @(rf/subscribe [:db])
        pattern (:pattern @(rf/subscribe [:view]))
        playing-step @(rf/subscribe [:playing-step pattern])]
    [:div
     [:div.muted "CHANNELS · " (if pattern (name pattern) "no pattern")]
     (if pattern
       (for [row (model/rack song pattern (set live) live-bars)]
         ^{:key (:channel row)} [rack-row pattern row playing-step])
       [:div.muted "Add a pattern to start."])]))

(defn picker []
  (let [{:keys [builtins samples songs]} @(rf/subscribe [:db])
        {:keys [picker]} @(rf/subscribe [:view])]
    (case picker
      :channel
      [:div.picker
       [:div.muted "BUILT-IN"]
       [:div.row (for [b builtins]
                   ^{:key b}
                   [:button {:on-click #(rf/dispatch [:action :add-channel [:builtin {:voice b}] (name b)])}
                    (name b)])]
       [:div.muted "SAMPLES (macOS sounds and brain/samples/)"]
       [:div.row (for [f samples
                       :let [n (-> f (str/split #"/") last (str/replace #"\.[^.]+$" ""))]]
                   ^{:key f}
                   [:button {:on-click #(rf/dispatch [:action :add-channel [:sampler {:sample f}] (str/lower-case n)])}
                    n])]]
      :open
      [:div.picker
       [:div.muted "SONGS IN brain/songs/"]
       (if (seq songs)
         [:div.row (for [n songs]
                     ^{:key n} [:button {:on-click #(rf/dispatch [:action :open n])} n])]
         [:div.muted "None yet. Save one first."])]
      nil)))

(defn playlist []
  (let [song @(rf/subscribe [:song])
        {:keys [step playing]} @(rf/subscribe [:playhead])
        pos @(rf/subscribe [:position])
        bars (model/visible-bars song)
        grid {:grid-template-columns (str "repeat(" bars ", 1fr)")}
        pct #(str (* 100 (/ % bars)) "%")]
    [:div
     [:div.row
      [:span.muted "PLAYLIST · click a bar to add a clip, a clip to remove it"]
      [:span.grow]
      [:button {:on-click #(rf/dispatch [:command {:cmd :song/set-loop :loop (not (:song/loop song))}])}
       (if (:song/loop song) "Loop: on" "Loop: off")]
      [:button {:on-click #(rf/dispatch [:command {:cmd :playlist/set :clips []}])} "Clear"]]
     [:div.ruler
      [:div.lane-name]
      [:div.bars {:style grid}
       (for [b (range bars)] ^{:key b} [:span (when (zero? (mod b 4)) b)])]]
     (for [{:keys [pattern clips] :as lane} (model/lanes song)]
       ^{:key pattern}
       [:div.lane
        [:div.lane-name (name pattern)]
        [:div.bars {:style grid}
         (for [b (range bars)]
           ^{:key b}
           [:div.bar {:class (when (zero? (mod b 4)) "four")
                      :on-click #(rf/dispatch [:command (model/click-playlist lane b)])}])
         (for [{:keys [start len]} clips]
           ^{:key start}
           [:div.clip {:class (when (and pos (<= start pos (dec (+ start len)))) "active")
                       :style {:left (pct start) :width (pct len)}}])
         (when (and playing pos)
           [:div.playhead {:style {:left (pct (+ pos (/ (or step 0) model/steps)))}}])]])]))

(defn status-line []
  (let [{:keys [file status]} @(rf/subscribe [:db])]
    [:div.status
     [:span.muted (if file (str "Song: " file) "Unsaved song")]
     (when status [:span.warn status])]))

(defn offline []
  [:div.offline
   "Not connected to the brain at " [:code (brain-url)] ". Start it with "
   [:code "cd brain && clojure -M:serve"] "; this page reconnects on its own."])

(defn root []
  (let [{:keys [brain? song]} @(rf/subscribe [:db])]
    [:div.app
     (when-not brain? [offline])
     (when song
       [:<>
        [top-bar]
        [:div.main
         [pattern-list]
         [:div.center
          [rack]
          [:div [:button {:on-click #(rf/dispatch [:toggle-picker :channel])} "+ Channel"]]
          [picker]]]
        [playlist]])
     [status-line]]))

;; --- Keys and start -----------------------------------------------------------------

(defn- on-key [e]
  (let [cmd? (or (.-metaKey e) (.-ctrlKey e))
        k (str/lower-case (.-key e))]
    (cond
      (and (= " " (.-key e)) (not= "BUTTON" (.. e -target -tagName)))
      (do (.preventDefault e) (rf/dispatch [:action :toggle-play]))
      (and cmd? (= "z" k) (.-shiftKey e)) (do (.preventDefault e) (rf/dispatch [:action :redo]))
      (and cmd? (= "z" k)) (do (.preventDefault e) (rf/dispatch [:action :undo]))
      (and cmd? (= "s" k)) (do (.preventDefault e) (rf/dispatch [:action :save])))))

(defonce ^:private react-root (atom nil))

(defn mount! []
  (rf/clear-subscription-cache!)
  (when-not @react-root
    (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
  (rdc/render @react-root [root]))

(defn init []
  (rf/dispatch-sync [:init])
  (.addEventListener js/window "keydown" on-key)
  (connect!)
  (mount!))
