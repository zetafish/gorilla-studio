(ns gorilla.engine-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [gorilla.engine :as engine]
            [gorilla.song :as song]
            [gorilla.wire :as wire])
  (:import (java.io DataInputStream)
           (java.net ServerSocket Socket)))

(defn- reset-engine-state! []
  (engine/stop-auto-connect!)
  (reset! engine/desired-playing true)
  (reset! engine/samples-root (System/getProperty "user.dir"))
  (reset! engine/last-rejection nil)
  (reset! @#'engine/epoch 0)
  (reset! @#'engine/synced nil)
  (reset! engine/playhead nil))

(use-fixtures :each (fn [t] (reset-engine-state!) (t) (reset-engine-state!)))

;; A fake engine: accepts one brain, says hello, then lets the test read
;; patches and write telemetry.

(defn- accept [^ServerSocket server engine-id epoch]
  (let [conn (.accept server)
        out (.getOutputStream conn)]
    (.setSoTimeout conn 5000)
    (wire/write-frame! out (wire/encode {:op :engine/hello :engine-id engine-id :epoch epoch}))
    {:conn conn :in (DataInputStream. (.getInputStream conn)) :out out}))

(defn- read-patch [{:keys [in]}] (wire/read-frame in))

(defn- rows
  "Rows set by an envelope's `:pattern/set-steps` ops."
  [envelope]
  (->> (:ops envelope) (filter #(= :pattern/set-steps (:op %))) (mapv :row)))

(defn- connect-to [server song engine-id epoch]
  (let [connected (future (engine/connect! song {:port (.getLocalPort ^ServerSocket server)}))
        fake (accept server engine-id epoch)]
    (is (= :connected (deref connected 5000 :timeout)))
    fake))

(defn- eventually [pred]
  (loop [n 0]
    (cond (pred) true
          (> n 200) false
          :else (do (Thread/sleep 10) (recur (inc n))))))

(def set-hat {:op :pattern/set-steps :pattern :drums :row :hat :steps (vec (repeat 16 1))})

(deftest invalid-envelopes-are-not-encoded
  (is (thrown? clojure.lang.ExceptionInfo
               (engine/frame {:epoch 1 :ops [(assoc set-hat :steps [1 2])]}))))

(deftest sends-a-snapshot-then-one-patch-per-change
  (with-open [server (ServerSocket. 0)]
    (let [song (atom song/default-song)
          fake (connect-to server song 7 0)
          snapshot (read-patch fake)]
      (is (= ["hat" "kick" "snare"] (rows snapshot)))
      (swap! song assoc-in [:patterns :drums :steps :hat 1] 1)
      (let [patch (read-patch fake)]
        (is (= (inc (:epoch snapshot)) (:epoch patch)))
        (is (= [["hat" [1 1 1 0 1 0 1 0 1 0 1 0 1 0 1 0]]]
               (map (juxt :row :steps) (:ops patch))))))))

(deftest transport-keeps-the-epoch-and-leaves-the-song-alone
  (with-open [server (ServerSocket. 0)]
    (let [song (atom song/default-song)
          fake (connect-to server song 7 0)
          snapshot (read-patch fake)]
      (engine/set-playing! false)
      (is (= {:epoch (:epoch snapshot) :ops [{:op :transport/set :playing false}]}
             (read-patch fake)))
      (is (= song/default-song @song)))))

(deftest transport-needs-a-connection
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Not connected"
                        (engine/set-playing! true))))

(deftest epochs-continue-from-the-engines
  (with-open [server (ServerSocket. 0)]
    (let [fake (connect-to server (atom song/default-song) 7 41)]
      (is (= 42 (:epoch (read-patch fake)))))))

(deftest reconnecting-sends-only-what-the-engine-is-missing
  (with-open [server (ServerSocket. 0)]
    (let [song (atom song/default-song)
          fake (connect-to server song 7 0)
          {:keys [epoch]} (read-patch fake)]
      (engine/disconnect!)
      (swap! song assoc-in [:patterns :drums :steps :kick 2] 1)
      (testing "same engine, same epoch: just the change made while away"
        (let [fake (connect-to server song 7 epoch)
              patch (read-patch fake)]
          (is (= ["kick"] (rows patch)))
          (engine/disconnect!)
          (testing "a restarted engine (new id) gets everything"
            (let [fake (connect-to server song 8 0)
                  snapshot (read-patch fake)]
              (is (= ["hat" "kick" "snare"] (rows snapshot)))
              (is (< (:epoch patch) (:epoch snapshot))))))))))

(deftest playhead-telemetry-is-kept
  (with-open [server (ServerSocket. 0)]
    (let [{:keys [out]} (connect-to server (atom song/default-song) 7 0)]
      (wire/write-frame! out (wire/encode {:op :engine/playhead :epoch 1 :playing true
                                           :bar 3 :step 15}))
      (is (eventually #(= 3 (:bar @engine/playhead))))
      (is (= 15 (:step @engine/playhead))))))

(deftest notices-when-the-engine-goes-away
  (with-open [server (ServerSocket. 0)]
    (let [{:keys [^Socket conn]} (connect-to server (atom song/default-song) 7 0)]
      (is (engine/connected?))
      (.close conn)
      (is (eventually #(not (engine/connected?)))))))

(deftest auto-connect-reconnects-and-resyncs
  (with-open [server (ServerSocket. 0)]
    (let [song (atom song/default-song)
          _ (engine/auto-connect! song {:port (.getLocalPort server) :interval-ms 20})
          first-engine (accept server 7 0)]
      (is (= ["hat" "kick" "snare"] (rows (read-patch first-engine))))
      (.close ^Socket (:conn first-engine))
      (testing "the engine restarts with a new id and gets the whole song again"
        (let [second-engine (accept server 9 0)]
          (is (= ["hat" "kick" "snare"] (rows (read-patch second-engine))))
          (is (eventually engine/connected?)))))))

(deftest a-rejected-song-change-resends-the-whole-song
  (with-open [server (ServerSocket. 0)]
    (let [song (atom song/default-song)
          {:keys [out] :as fake} (connect-to server song 7 0)
          _ (read-patch fake)
          _ (swap! song assoc-in [:patterns :drums :steps :hat 1] 1)
          {:keys [epoch]} (read-patch fake)]
      (wire/write-frame! out (wire/encode {:op :engine/rejected :epoch epoch
                                           :reason "nope" :ops ["pattern/set-steps"]}))
      (let [resync (read-patch fake)]
        (is (= :song/reset (:op (first (:ops resync)))))
        (is (= ["hat" "kick" "snare"] (rows resync))))
      (testing "a refused snapshot is not resent"
        (wire/write-frame! out (wire/encode {:op :engine/rejected :epoch (inc epoch)
                                             :reason "bad sample" :ops ["song/reset"]}))
        (is (eventually #(= "bad sample" (:reason @engine/last-rejection))))
        (swap! song assoc-in [:patterns :drums :steps :hat 2] 0)
        (is (= :song/reset (:op (first (:ops (read-patch fake)))))
            "the next edit tries the whole song again")))))

(deftest a-rejected-live-op-needs-no-resync
  (with-open [server (ServerSocket. 0)]
    (let [song (atom song/default-song)
          {:keys [out] :as fake} (connect-to server song 7 0)]
      (read-patch fake)
      (wire/write-frame! out (wire/encode {:op :engine/rejected :epoch 1
                                           :reason "late" :ops ["pattern/schedule-bar"]}))
      (is (eventually #(some? @engine/last-rejection)))
      (swap! song assoc-in [:patterns :drums :steps :hat 1] 1)
      (is (= ["hat"] (rows (read-patch fake))) "just the edit, no snapshot"))))

(deftest a-new-engine-gets-the-transport-back
  (with-open [server (ServerSocket. 0)]
    (let [song (atom song/default-song)
          fake (connect-to server song 7 0)]
      (read-patch fake)
      (engine/set-playing! false)
      (read-patch fake)
      (engine/disconnect!)
      (let [new-engine (connect-to server song 8 0)]
        (read-patch new-engine)
        (is (= [{:op :transport/set :playing false}] (:ops (read-patch new-engine))))))))

(deftest moving-the-samples-root-reloads-relative-samples
  (with-open [server (ServerSocket. 0)]
    (let [song (atom (assoc-in song/default-song [:channels :clap :gen]
                               [:sampler {:sample "assets/clap.wav"}]))
          fake (connect-to server song 7 0)
          _ (read-patch fake)
          _ (reset! engine/samples-root "/elsewhere")
          _ (swap! song assoc :song/bpm 100)
          ops (:ops (read-patch fake))]
      (is (= "/elsewhere/assets/clap.wav"
             (get-in (first (filter #(= :channel/set (:op %)) ops)) [:generator :sample]))))))

(deftest relative-samples-resolve-against-the-samples-root
  (with-open [server (ServerSocket. 0)]
    (let [song (atom (assoc-in song/default-song [:channels :clap :gen]
                               [:sampler {:sample "clap.wav"}]))
          fake (connect-to server song 7 0)
          clap (->> (:ops (read-patch fake))
                    (filter #(= "clap" (:channel %)))
                    first)]
      (is (= (str (java.io.File. (System/getProperty "user.dir") "clap.wav"))
             (get-in clap [:generator :sample]))))))
