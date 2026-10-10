(ns gorilla.file
  "Songs on disk. A song is a folder:

    my-song/
      song.edn     the song value, plain data
      assets/      a copy of every sample the song plays

  `song.edn` is written in a fixed order (sorted keys, one entry per line,
  each row on one line) so it diffs cleanly in git. Sample paths in it are
  relative to the folder.

  Live patterns are saved as their source text plus `materialized-bars` bars
  of output. Opening a song reads data only and evaluates nothing: live rows
  come back frozen, replaying the saved bars. `gorilla.live/activate!` runs the
  saved source, in SCI, when you ask for it."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [gorilla.engine :as engine]
            [gorilla.live :as live]
            [gorilla.song :as song]
            [gorilla.studio :as studio]
            [malli.core :as m]
            [malli.error :as me])
  (:import (java.io File)
           (java.nio.file CopyOption Files StandardCopyOption)
           (java.util Arrays)))

(def format-version 1)

(def materialized-bars 16)

(def ^:private max-file-bytes (* 16 1024 1024))

(def SongFile
  [:map {:closed true}
   [:format [:= format-version]]
   [:song :map]
   [:live {:optional true}
    [:map-of [:tuple :keyword :keyword]
     [:map {:closed true}
      [:source :string]
      [:bars [:vector {:min 1 :max 1024} song/Steps]]]]]])

(defonce ^{:doc "Folder of the song last opened or saved."} current (atom nil))

;; --- Writing EDN in a stable layout --------------------------------------------

(defn- key-order [k] [(str (type k)) (pr-str k)])

(defn- inline
  "One-line EDN with map keys sorted, so the text never depends on hash order."
  [x]
  (cond
    (map? x) (str "{" (str/join ", " (for [[k v] (sort-by (comp key-order key) x)]
                                       (str (inline k) " " (inline v))))
                  "}")
    (vector? x) (str "[" (str/join " " (map inline x)) "]")
    (seq? x) (str "(" (str/join " " (map inline x)) ")")
    (set? x) (str "#{" (str/join " " (sort (map inline x))) "}")
    :else (pr-str x)))

(def ^:private width 100)

(defn- layout
  "EDN for `x`, starting at column `col`."
  [x col]
  (let [one-line (inline x)
        pad (str "\n" (apply str (repeat (inc col) \space)))]
    (cond
      (or (not (coll? x))
          (<= (+ col (count one-line)) width)
          (and (vector? x) (not-any? coll? x)))
      one-line

      (map? x)
      (str "{" (str/join pad (for [[k v] (sort-by (comp key-order key) x)
                                   :let [k (inline k)]]
                               (str k " " (layout v (+ col 2 (count k))))))
           "}")

      :else
      (str "[" (str/join pad (map #(layout % (inc col)) x)) "]"))))

(defn ->edn
  "The text of a song file for `data`."
  [data]
  (str ";; Gorilla Studio song, format " format-version ".\n"
       ";; Plain data: opening it evaluates nothing. Live pattern source runs only\n"
       ";; when you call (gorilla.live/activate!).\n"
       (layout data 0) "\n"))

;; --- Samples -------------------------------------------------------------------

(defn- same-bytes? [^File a ^File b]
  (Arrays/equals (Files/readAllBytes (.toPath a)) (Files/readAllBytes (.toPath b))))

(defn- asset-target
  "Where `src` goes in `assets`: its own name, or name-2, name-3, ... when a
  different file already has that name."
  ^File [^File assets ^File src]
  (let [name (.getName src)
        [base ext] (let [i (str/last-index-of name ".")]
                     (if (and i (pos? i)) [(subs name 0 i) (subs name i)] [name ""]))]
    (->> (cons name (map #(str base "-" % ext) (iterate inc 2)))
         (map #(io/file assets %))
         (some #(when (or (not (.exists ^File %)) (same-bytes? % src)) %)))))

(defn- bundle-samples
  "Copies every sample into `dir`/assets and returns the song with paths
  relative to `dir`. A sample that can't be found keeps its path, with a
  warning, so saving never loses the rest of the song."
  [song ^File dir]
  (let [assets (io/file dir "assets")]
    (reduce-kv
     (fn [song channel {[kind {:keys [sample]}] :gen}]
       (if (not= :sampler kind)
         song
         (let [src (io/file (engine/sample-path sample))
               dir-path (.toPath (.getCanonicalFile dir))]
           (if-not (.isFile src)
             (do (println "gorilla.file: sample not found, saved as is:" (str src))
                 song)
             (let [src (.getCanonicalFile src)
                   target (if (.startsWith (.toPath src) (.toPath (.getCanonicalFile assets)))
                            src
                            (let [t (asset-target assets src)]
                              (.mkdirs assets)
                              (when-not (.exists t)
                                (Files/copy (.toPath src) (.toPath t)
                                            ^"[Ljava.nio.file.CopyOption;"
                                            (into-array CopyOption [StandardCopyOption/COPY_ATTRIBUTES])))
                              (.getCanonicalFile t)))]
               (assoc-in song [:channels channel :gen 1 :sample]
                         (str (.relativize dir-path (.toPath target)))))))))
     song
     (:channels song))))

;; --- Save and open ------------------------------------------------------------

(defn save!
  "Saves the current song and its live patterns to folder `dir` (created if
  needed), copying samples into `dir`/assets. Without `dir`, saves where the
  song was last opened or saved. The song in memory is unchanged."
  ([] (save! (or @current (throw (ex-info "No folder yet: (save! \"path/to/song\")" {})))))
  ([dir]
   (let [dir (.getCanonicalFile (io/file dir))
         _ (.mkdirs dir)
         data (cond-> {:format format-version
                       :song (bundle-samples @studio/song dir)}
                (seq @live/patterns) (assoc :live (live/materialize materialized-bars)))
         target (io/file dir "song.edn")
         tmp (io/file dir "song.edn.tmp")]
     (spit tmp (->edn data))
     ;; Rename, so a crash mid-save never leaves half a file.
     (Files/move (.toPath tmp) (.toPath target)
                 ^"[Ljava.nio.file.CopyOption;"
                 (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING
                                         StandardCopyOption/ATOMIC_MOVE]))
     (reset! current (str dir))
     (str target))))

(defn read-song-file
  "Reads and checks a song folder's song.edn without touching any state.
  Throws ex-info describing the first problem."
  [dir]
  (let [f (io/file dir "song.edn")]
    (when-not (.isFile f)
      (throw (ex-info (str "No song.edn in " dir) {:dir (str dir)})))
    (when (> (.length f) max-file-bytes)
      (throw (ex-info "song.edn is too large" {:bytes (.length f)})))
    ;; clojure.edn reads data only: no eval, no reader macros, no unknown tags.
    (let [data (edn/read-string {:readers {}} (slurp f))]
      (when-not (m/validate SongFile data)
        (throw (ex-info "Not a Gorilla Studio song file"
                        {:errors (me/humanize (m/explain SongFile data))})))
      (when-let [errors (song/explain (:song data))]
        (throw (ex-info "The song in the file is invalid"
                        {:errors (me/humanize errors)})))
      (doseq [[[pattern row]] (:live data)
              :when (not (get-in data [:song :patterns pattern :steps row]))]
        (throw (ex-info (str "Live pattern for missing row " [pattern row]) {})))
      data)))

(defn open!
  "Opens a song folder: replaces the song (undo brings the old one back) and
  restores live patterns frozen. Evaluates nothing from the file."
  [dir]
  (let [dir (.getCanonicalFile (io/file dir))
        {:keys [song live]} (read-song-file dir)]
    (doseq [[channel {[kind {:keys [sample]}] :gen}] (:channels song)
            :when (and (= :sampler kind)
                       (not (.isFile (io/file (let [f (io/file sample)]
                                                (if (.isAbsolute f) f (io/file dir sample)))))))]
      (println "gorilla.file: missing sample for" channel "-" sample))
    (live/clear!)
    (reset! engine/samples-root (str dir))
    (studio/dispatch! {:cmd :song/replace :song song})
    (doseq [[[pattern row] {:keys [source bars]}] live]
      (live/set-frozen! pattern row source bars))
    (reset! current (str dir))
    {:dir (str dir)
     :channels (count (:channels song))
     :patterns (count (:patterns song))
     :live (count live)}))
