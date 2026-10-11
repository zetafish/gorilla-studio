(ns gorilla.ui.model-test
  (:require [clojure.test :refer [deftest is testing]]
            [gorilla.command :as command]
            [gorilla.diff :as diff]
            [gorilla.song :as song]
            [gorilla.ui.model :as model]))

(def s (-> song/default-song
           (assoc-in [:channels :bass :gen] [:builtin {:voice :bass}])
           (assoc-in [:channels :pop :gen] [:sampler {:sample "/System/Library/Sounds/Pop.aiff"}])
           (assoc-in [:patterns :low :notes :bass] [{:t 0 :len 4 :pitch 33 :vel 1}])))

(deftest the-rack-lists-every-channel
  (let [rows (model/rack s :drums #{[:drums :hat]} {[:drums :hat] [1 1 1 1]})]
    (is (= [:bass :hat :kick :pop :snare] (map :channel rows)))
    (is (= ["bass" "hat" "kick" "Pop.aiff" "snare"] (map :gen-label rows)))
    (is (= [:none :steps :steps :none :steps] (map :kind rows)))
    (is (= [false true false false false] (map :live? rows)))
    (is (= [1 1 1 1] (:played (second rows)))))
  (is (= [:notes :none :none :none :none] (map :kind (model/rack s :low #{} {})))))

(deftest rack-clicks
  (let [width (+ (:name-w model/layout) (* 16 40))]
    (is (nil? (model/rack-hit width 10 10)) "the name column isn't a step")
    (is (= {:row 1 :step 0} (model/rack-hit width (+ (:name-w model/layout) 5) 35)))
    (is (= {:row 0 :step 15} (model/rack-hit width (- width 1) 1)))
    (is (nil? (model/rack-hit width width 1))))
  (let [[bass hat] (model/rack s :drums #{} {})
        run #(command/run s %)]
    (testing "a step row toggles"
      (is (= 1 (get-in (run (model/click-step :drums hat 1)) [:patterns :drums :steps :hat 1]))))
    (testing "a channel without a row gets one"
      (is (= [1 0 0] (take 3 (get-in (run (model/click-step :drums bass 0)) [:patterns :drums :steps :bass])))))
    (testing "a note row adds and removes notes"
      (let [bass (first (model/rack s :low #{} {}))
            added (run (model/click-step :low bass 8))]
        (is (= [0 8] (map :t (get-in added [:patterns :low :notes :bass]))))
        (is (= 33 (:pitch (second (get-in added [:patterns :low :notes :bass])))) "same pitch as the last note")
        (is (= [] (get-in (run (model/click-step :low bass 0)) [:patterns :low :notes :bass])) "clicking the only note removes it")))))

(deftest playlist-clicks
  (let [song (assoc s :playlist [{:pattern :drums :start 4 :len 4}])
        [drums] (model/lanes song)]
    (is (= :drums (:pattern drums)))
    (is (= {:cmd :playlist/remove :pattern :drums :start 4} (model/click-playlist drums 6)))
    (is (= {:cmd :playlist/add :pattern :drums :start 0 :len 4} (model/click-playlist drums 0)))
    (is (= {:cmd :playlist/add :pattern :drums :start 2 :len 2} (model/click-playlist drums 2))
        "stops at the next clip")
    (is (= 32 (model/visible-bars song)))
    (is (= 40 (model/visible-bars (assoc song :playlist [{:pattern :drums :start 30 :len 2}]))))
    (is (= {:lane 1 :bar 0} (model/playlist-hit 1110 32 (+ 1 (:lane-name-w model/layout)) 30)))))

(deftest song-position-and-active-patterns
  (let [song (assoc s :playlist [{:pattern :drums :start 0 :len 2} {:pattern :low :start 2 :len 2}])]
    (is (nil? (model/song-position s 5)))
    (is (= 5 (model/song-position song 5)))
    (is (= 1 (model/song-position (assoc song :song/loop true) 5)))
    (is (= #{:low} (model/active-patterns song 3)))
    (is (= #{} (model/active-patterns song 9)))
    (is (= #{:drums :low} (model/active-patterns s nil)))))

(deftest new-patterns-and-their-clips
  (let [song (-> s
                 (command/run {:cmd :pattern/add :pattern (model/next-name "p" (:patterns s))})
                 (command/run {:cmd :playlist/add :pattern :p1 :start 0 :len 4}))]
    (is (= {:steps {}} (get-in song [:patterns :p1])))
    (is (thrown? clojure.lang.ExceptionInfo (command/run song {:cmd :pattern/add :pattern :p1})))
    (is (not-any? #(= :song/set-playlist (:op %)) (diff/diff s song))
        "a clip of an empty pattern isn't sent; the engine doesn't know the pattern yet")))
