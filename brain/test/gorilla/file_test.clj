(ns gorilla.file-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [gorilla.engine :as engine]
            [gorilla.file :as file]
            [gorilla.live :as live]
            [gorilla.pattern :as p]
            [gorilla.song :as song]
            [gorilla.studio :as studio])
  (:import (java.nio ByteBuffer ByteOrder)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def ^:private root (System/getProperty "user.dir"))

(defn- reset-all! []
  (live/clear!)
  (reset! @#'live/state {:sent {}})
  (studio/reset-song!)
  (reset! engine/samples-root root)
  (reset! file/current nil))

(use-fixtures :each (fn [t] (reset-all!) (t) (reset-all!)))

(defn- temp-dir []
  (.toFile (Files/createTempDirectory "gorilla-song" (make-array FileAttribute 0))))

(defn- wav!
  "A tiny 16-bit mono WAV whose content depends on `seed`."
  [file seed]
  (let [frames (short-array (map #(short (* seed %)) (range 4)))
        data-len (* 2 (alength frames))
        buf (doto (ByteBuffer/allocate (+ 44 data-len)) (.order ByteOrder/LITTLE_ENDIAN))]
    (.put buf (.getBytes "RIFF")) (.putInt buf (+ 36 data-len)) (.put buf (.getBytes "WAVEfmt "))
    (.putInt buf 16) (.putShort buf 1) (.putShort buf 1) (.putInt buf 48000) (.putInt buf 96000)
    (.putShort buf 2) (.putShort buf 16) (.put buf (.getBytes "data")) (.putInt buf data-len)
    (doseq [f frames] (.putShort buf f))
    (io/make-parents file)
    (io/copy (.array buf) (io/file file))
    (str file)))

(defn- song-with-samples [dir]
  (let [a (wav! (io/file dir "src" "a" "clap.wav") 1)
        b (wav! (io/file dir "src" "b" "clap.wav") 2)]
    (-> song/default-song
        (assoc-in [:channels :clap :gen] [:sampler {:sample a}])
        (assoc-in [:channels :clap2 :gen] [:sampler {:sample b}])
        (assoc-in [:patterns :drums :steps :clap] (p/steps "....x..."))
        (assoc-in [:patterns :fills :steps :clap2] (p/steps "x.x.")))))

(deftest the-layout-is-stable-and-readable
  (let [a (into {} (map vector (range 20) (range 20)))
        b (into (sorted-map-by >) a)]
    (is (= (file/->edn {:x a}) (file/->edn {:x b})) "hash order never shows"))
  (let [text (file/->edn {:format 1 :song song/default-song})]
    (is (str/includes? text ":kick [1 0 0 0 1 0 0 0 1 0 0 0 1 0 0 0]") "a row stays on one line")
    (is (= song/default-song (:song (read-string (str/replace text #";;[^\n]*\n" "")))))))

(deftest save-and-open-round-trip
  (let [dir (temp-dir)
        song-dir (io/file dir "my song")]
    (studio/dispatch! {:cmd :song/replace :song (song-with-samples dir)})
    (live/set-pattern! :drums :hat (fn [bar] (p/euclid (+ 3 (mod bar 4)) 16)) "(fn [bar] ...)")
    (let [saved-live (live/materialize file/materialized-bars)
          path (file/save! song-dir)
          text (slurp path)]
      (testing "samples are copied and paths made relative"
        (is (str/includes? text "\"assets/clap.wav\""))
        (is (str/includes? text "\"assets/clap-2.wav\"") "same name, different sound")
        (is (.isFile (io/file song-dir "assets" "clap-2.wav"))))
      (testing "saving again changes nothing"
        (file/save!)
        (is (= text (slurp path)))
        (is (= 2 (count (.list (io/file song-dir "assets"))))))

      (reset-all!)
      (let [summary (file/open! song-dir)]
        (is (= {:channels 5 :patterns 2 :live 1} (dissoc summary :dir))))
      (testing "the song comes back, samples resolving inside the folder"
        (is (= "assets/clap.wav" (get-in @studio/song [:channels :clap :gen 1 :sample])))
        (is (= (str (io/file (.getCanonicalFile song-dir) "assets" "clap.wav"))
               (engine/sample-path "assets/clap.wav"))))
      (testing "live patterns come back frozen, replaying the same bars"
        (let [{:keys [f frozen source]} (get @live/patterns [:drums :hat])]
          (is (= (get-in saved-live [[:drums :hat] :bars]) frozen))
          (is (= "(fn [bar] ...)" source))
          (is (= (p/euclid 4 16) (f 1) (f 17)) "loops after 16 bars"))))))

(deftest opening-evaluates-nothing
  (let [dir (temp-dir)
        canary (io/file dir "canary")
        source (str "(do (spit " (pr-str (str canary)) " 1) (fn [bar] \"x\"))")]
    (spit (io/file dir "song.edn")
          (file/->edn {:format 1
                       :song song/default-song
                       :live {[:drums :hat] {:source source :bars [(p/steps "x...")]}}}))
    (file/open! dir)
    (is (not (.exists canary)))
    (is (= source (:source (get @live/patterns [:drums :hat]))))))

(deftest bad-files-are-refused-and-change-nothing
  (let [dir (temp-dir)
        attempt (fn [text]
                  (spit (io/file dir "song.edn") text)
                  (try (file/open! dir) :opened
                       (catch Exception e (ex-message e))))]
    (is (re-find #"No song.edn" (try (file/open! (io/file dir "nope")) (catch Exception e (ex-message e)))))
    (is (string? (attempt "#=(java.lang.System/exit 1)")) "no reader eval")
    (is (string? (attempt "#inst \"2020\"")) "no tagged literals")
    (is (re-find #"Not a Gorilla" (attempt "{:format 2 :song {}}")))
    (is (re-find #"invalid" (attempt (pr-str {:format 1 :song (assoc song/default-song :song/bpm 0)}))))
    (is (re-find #"missing row" (attempt (pr-str {:format 1 :song song/default-song
                                                  :live {[:drums :cowbell] {:source "" :bars [(p/steps "x")]}}}))))
    (is (= song/default-song @studio/song))
    (is (empty? @live/patterns))))

(deftest activate-runs-saved-source-in-the-sandbox
  (let [dir (temp-dir)
        canary (io/file dir "canary")]
    (spit (io/file dir "song.edn")
          (file/->edn
           {:format 1
            :song (-> song/default-song
                      (assoc-in [:channels :lead :gen] [:builtin {:voice :lead}])
                      (assoc-in [:patterns :melody :notes :lead] []))
            :live {[:drums :hat] {:source "(fn [bar] (euclid (+ 3 (mod bar 4)) 16))"
                                  :bars [(p/steps "x...")]}
                   [:melody :lead] {:source "(fn [bar] (rhythm [1/4] (markov-walk {:seed bar :len 4 :scale (scale :a4 :minor)})))"
                                    :bars [(p/steps "x...")]}
                   [:drums :kick] {:source (str "(fn [bar] (spit " (pr-str (str canary)) " 1) \"x\")")
                                   :bars [(p/steps "x.......")]}
                   [:drums :snare] {:source "(fn [bar] (future 1))" :bars [(p/steps "....x...")]}}}))
    (file/open! dir)
    (is (= (p/steps "x...") ((:f (get @live/patterns [:drums :hat])) 5)) "frozen until activated")
    (let [{:keys [active failed]} (live/activate!)]
      (is (= [[:drums :hat] [:melody :lead]] active))
      (is (re-find #"spit" (get failed [:drums :kick])))
      (is (re-find #"future" (get failed [:drums :snare]))))
    (is (= (p/euclid 4 16) ((:f (get @live/patterns [:drums :hat])) 5)) "generating again")
    (is (some? (:frozen (get @live/patterns [:drums :kick]))) "a failed pattern stays frozen")
    (is (not (.exists canary)))))

(deftest undo-after-open-brings-back-patterns-and-samples
  (let [a (temp-dir)
        b (temp-dir)]
    (studio/dispatch! {:cmd :song/replace :song (song-with-samples a)})
    (live/set-pattern! :drums :hat (constantly "x.x.") "a-hat")
    (file/save! a)
    (studio/dispatch! {:cmd :song/replace :song song/default-song})
    (live/clear!)
    (live/set-pattern! :drums :kick (constantly "xxxx") "b-kick")
    (file/save! b)

    (file/open! a)
    (is (= (str (.getCanonicalFile a)) @engine/samples-root))
    (file/open! b)
    (is (= #{[:drums :kick]} (set (keys @live/patterns))))
    (studio/undo!)
    (is (= #{[:drums :hat]} (set (keys @live/patterns))) "a's patterns are back")
    (is (= (str (.getCanonicalFile a)) @engine/samples-root) "and samples resolve in a again")
    (studio/redo!)
    (is (= #{[:drums :kick]} (set (keys @live/patterns))))))
