(ns gorilla.ui.app-test
  "The UI, drawn and clicked without a window."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [gorilla.engine :as engine]
            [gorilla.live :as live]
            [gorilla.song :as song]
            [gorilla.studio :as studio]
            [gorilla.ui.app :as app]
            [gorilla.ui.model :as model]
            [gorilla.ui.snapshot :as snap]))

(defn- reset-all! []
  (live/clear!)
  (studio/reset-song!)
  (reset! engine/playhead nil)
  (reset! app/*view {:pattern :drums :picker nil :status nil})
  (app/sync-signals!))

(use-fixtures :each (fn [t] (reset-all!) (t) (reset-all!)))

(def width 790) ; name column 150 + 16 cells of 40

(defn- with-mounted
  "Mounts `comp` fresh, draws it (for layout), runs `f` on it, unmounts."
  [comp w h f]
  (let [m (snap/mount comp w h)]
    (try (snap/draw! m) (f m) (finally (snap/unmount! m)))))

(defn- cell-center [row step]
  [(+ (:name-w model/layout) (* step 40) 20) (+ (* row (:row-h model/layout)) 15)])

(defn- click-rack! [row step]
  (with-mounted app/rack-canvas width 200
    #(apply snap/click! % (cell-center row step)))
  (app/sync-signals!))

(defn- steps-of [row] (get-in @studio/song [:patterns :drums :steps row]))

(deftest the-whole-window-draws-in-every-state
  (doseq [setup [(fn [])
                 #(do (studio/dispatch! {:cmd :song/replace :song song/empty-song})
                      (app/sync-signals!))
                 #(swap! app/*view assoc :picker :channel)
                 #(swap! app/*view assoc :picker :open)
                 #(reset! engine/playhead {:bar 3 :step 7 :playing true :epoch 1})]]
    (setup)
    (with-mounted app/root 1180 760 (fn [m] (is (some? (snap/draw! m)))))))

(deftest clicking-the-rack-edits-the-song
  ;; channels are sorted: hat, kick, snare
  (click-rack! 0 1)
  (is (= 1 (get (steps-of :hat) 1)) "toggled on")
  (click-rack! 0 1)
  (is (= 0 (get (steps-of :hat) 1)) "and off again")
  (testing "the × removes a channel"
    (with-mounted app/rack-canvas width 200
      #(snap/click! % (- (:name-w model/layout) 10) 15))
    (is (nil? (get-in @studio/song [:channels :hat]))))
  (testing "every click is undoable"
    (studio/undo!) (studio/undo!) (studio/undo!)
    (is (= song/default-song @studio/song))))

(deftest clicking-the-playlist-arranges
  (let [lane-y (+ 16 13)
        bar-x (fn [bar] (+ (:lane-name-w model/layout) (* bar (/ (- width (:lane-name-w model/layout)) 32)) 5))
        click! (fn [bar]
                 (with-mounted app/playlist-canvas width 100 #(snap/click! % (bar-x bar) lane-y))
                 (app/sync-signals!))]
    (click! 2)
    (is (= [{:pattern :drums :start 2 :len 4}] (:playlist @studio/song)))
    (click! 3)
    (is (= [] (:playlist @studio/song)) "clicking the clip removes it")
    (studio/undo!)
    (is (= 1 (count (:playlist @studio/song))))))

(deftest refused-actions-show-a-status-instead-of-throwing
  (#'app/run! {:cmd :song/set-bpm :bpm 1000})
  (is (re-find #"Invalid params" (:status @app/*view))))

(deftest a-png-for-humans
  ;; Not an assertion on pixels: writes target/ui.png so a person can look.
  (let [f (io/file "target" "ui.png")]
    (io/make-parents f)
    (reset! engine/playhead {:bar 1 :step 4 :playing true :epoch 1})
    (with-mounted app/root 1180 760 #(snap/png! % f))
    (is (.isFile f))))
