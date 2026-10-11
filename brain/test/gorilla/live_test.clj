(ns gorilla.live-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [gorilla.engine :as engine]
            [gorilla.live :as live]
            [gorilla.pattern :as p]
            [gorilla.studio :as studio]
            [gorilla.wire :as wire])
  (:import (java.io DataInputStream)
           (java.net ServerSocket)))

(defn- reset-all! []
  (engine/stop-auto-connect!)
  (reset! live/patterns {})
  (reset! @#'live/state {:sent {}})
  (reset! @#'engine/synced nil)
  (reset! engine/playhead nil)
  (studio/reset-song!))

(use-fixtures :each (fn [t] (reset-all!) (t) (reset-all!)))

(def all (vec (repeat 16 1)))

;; --- plan --------------------------------------------------------------------

(def hat {[:drums :hat] {:f (fn [bar] (p/euclid (inc bar) 16))}})

(deftest plan-sends-each-bar-once
  (let [{:keys [ops sent]} (live/plan hat {} 3 4)]
    (is (= [[:hat 3 (p/euclid 4 16)] [:hat 4 (p/euclid 5 16)]]
           (map (juxt :row :bar :steps) ops)))
    (is (= :pattern/schedule-bar (:op (first ops))))
    (testing "nothing changed: nothing to send, old bars forgotten"
      (let [again (live/plan hat sent 4 5)]
        (is (= [5] (map :bar (:ops again))))
        (is (= #{4 5} (set (map last (keys (:sent again))))))))
    (testing "a redefined pattern re-sends queued bars"
      (let [loud {[:drums :hat] {:f (constantly all)}}]
        (is (= [3 4] (map :bar (:ops (live/plan loud sent 3 4)))))))))

(deftest plan-sends-notes-for-melodic-patterns
  (let [lead {[:melody :lead] {:f (fn [bar] (p/rhythm [1/4] [(+ 60 bar)]))}}
        {:keys [ops]} (live/plan lead {} 2 2)]
    (is (= [{:op :pattern/schedule-notes :pattern :melody :row :lead :bar 2
             :notes [{:t 0 :len 4 :pitch 62 :vel 0.8} {:t 4 :len 4 :pitch 62 :vel 0.8}
                     {:t 8 :len 4 :pitch 62 :vel 0.8} {:t 12 :len 4 :pitch 62 :vel 0.8}]}]
           ops))))

(deftest plan-reports-failing-patterns-and-skips-them
  (let [{:keys [ops errors]} (live/plan {[:drums :hat] {:f (fn [bar] (if (= bar 4) (/ 1 0) "x..."))}
                                         [:drums :kick] {:f (constantly "x...")}}
                                        {} 3 4)]
    (is (= [[:hat 3] [:kick 3] [:kick 4]] (map (juxt :row :bar) ops)))
    (is (re-find #"ArithmeticException" (get errors [:drums :hat])))))

;; --- against a fake engine ----------------------------------------------------

(defn- fake-engine [^ServerSocket server]
  (let [connected (future (engine/connect! studio/song {:port (.getLocalPort server)}))
        conn (.accept server)
        out (.getOutputStream conn)]
    (.setSoTimeout conn 5000)
    (wire/write-frame! out (wire/encode {:op :engine/hello :engine-id 1 :epoch 0}))
    (let [in (DataInputStream. (.getInputStream conn))]
      (is (= :connected (deref connected 5000 :timeout)))
      (wire/read-frame in) ; the song snapshot
      {:in in :out out})))

(defn- playhead! [{:keys [out]} bar]
  (wire/write-frame! out (wire/encode {:op :engine/playhead :epoch 1 :playing true
                                       :bar bar :step 0})))

(defn- next-ops [{:keys [in]}]
  (:ops (wire/read-frame in)))

(defn- wait-for-bar [bar]
  (loop [n 0]
    (when (and (not= bar (:bar @engine/playhead)) (< n 200))
      (Thread/sleep 10)
      (recur (inc n)))))

(deftest live-patterns-reach-the-engine-ahead-of-time
  (with-open [server (ServerSocket. 0)]
    (let [fake (fake-engine server)]
      (playhead! fake 10)
      (wait-for-bar 10)
      (live/set-pattern! :drums :hat (constantly "x...") "src")
      (is (= [["hat" 11] ["hat" 12]] (map (juxt :row :bar) (next-ops fake))))

      (testing "redefining re-sends the queued bars"
        (live/set-pattern! :drums :hat (constantly all) "src")
        (let [ops (next-ops fake)]
          (is (= [11 12] (map :bar ops)))
          (is (= all (:steps (first ops))))))

      (testing "as the playhead moves, only the new bar goes out"
        (playhead! fake 11)
        (is (= [13] (map :bar (next-ops fake)))))

      (testing "removing a pattern restores the song's row"
        (live/remove-pattern! :hat)
        (is (= [{:op :pattern/set-steps :pattern "drums" :row "hat"
                 :steps (get-in @studio/song [:patterns :drums :steps :hat])}]
               (next-ops fake))))

      (testing "play queues the first bars in the same envelope"
        (live/set-pattern! :drums :kick (constantly "x.x.") "src")
        (next-ops fake)
        (live/play!)
        (is (= [:transport/set :pattern/schedule-bar :pattern/schedule-bar :pattern/schedule-bar]
               (map :op (next-ops fake))))))))

(deftest bad-patterns-are-refused-up-front
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No row"
                        (live/set-pattern! :drums :cowbell (constantly "x") "src")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"fails on bar 0"
                        (live/set-pattern! :drums :hat (fn [_] (throw (Exception. "boom"))) "src")))
  (is (empty? @live/patterns)))
