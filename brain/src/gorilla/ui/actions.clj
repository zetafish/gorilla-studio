(ns gorilla.ui.actions
  "What UI controls do, shared by every UI (HumbleUI, the web UI). Each
  action returns a status message, or nil when all went well, instead of
  throwing, so a UI can show it."
  (:require [clojure.java.io :as io]
            [gorilla.engine :as engine]
            [gorilla.file :as file]
            [gorilla.live :as live]
            [gorilla.studio :as studio]
            [gorilla.ui.model :as model]))

(def songs-dir "Where Save puts new songs and Open looks for them." "songs")

(defn run!
  "Dispatches a command; returns nil, or why it was refused."
  [cmd]
  (try
    (studio/dispatch! cmd)
    nil
    (catch Exception e
      (ex-message e))))

(defn playing? [] (boolean (:playing @engine/playhead)))

(defn play! []
  (try (live/play!) nil (catch Exception e (ex-message e))))

(defn stop! []
  (try (engine/set-playing! false) nil (catch Exception e (ex-message e))))

(defn toggle-play! []
  (if (playing?) (stop!) (play!)))

(defn undo! [] (studio/undo!) nil)

(defn redo! [] (studio/redo!) nil)

(defn song-folders
  "Names of the song folders in `songs-dir`."
  []
  (->> (.listFiles (io/file songs-dir))
       (filter #(.isFile (io/file % "song.edn")))
       (map #(.getName ^java.io.File %))
       sort
       vec))

(defn save!
  "Saves to the folder the song came from, or a new songs/untitled-N."
  []
  (try
    (let [dir (or @file/current
                  (let [taken (set (song-folders))]
                    (str (io/file songs-dir (first (remove taken (map #(str "untitled-" %) (iterate inc 1))))))))]
      (file/save! dir)
      (str "Saved " dir))
    (catch Exception e (str "Save failed: " (ex-message e)))))

(defn open!
  "Opens songs-dir/`name`. Only names listed by `song-folders` are allowed,
  so a UI can't open arbitrary paths."
  [name]
  (if-not (some #{name} (song-folders))
    (str "No song called " name)
    (try
      (file/open! (io/file songs-dir name))
      (str "Opened " name)
      (catch Exception e (str "Open failed: " (ex-message e))))))

(def builtins [:kick :snare :hat :bass :lead])

(defn sample-files
  "Sample files offered by \"+ Channel\": macOS system sounds and samples/."
  []
  (->> [(io/file "/System/Library/Sounds") (io/file "samples")]
       (mapcat #(.listFiles ^java.io.File %))
       (filter #(re-find #"(?i)\.(wav|aiff?|flac)$" (.getName ^java.io.File %)))
       (sort-by #(.getName ^java.io.File %))
       (mapv str)))

(defn add-channel!
  "Adds a channel named after `base-name` (or base-name2, ...) playing `gen`."
  [gen base-name]
  (let [channels (:channels @studio/song)
        wanted (keyword base-name)
        channel (if (contains? channels wanted) (model/next-name (name base-name) channels) wanted)]
    (run! {:cmd :channel/set :channel channel :gen gen})))

(defn add-pattern!
  "Adds an empty pattern; returns [its id, status]."
  []
  (let [p (model/next-name "pattern" (:patterns @studio/song))]
    [p (run! {:cmd :pattern/add :pattern p})]))

(defn live-bars
  "What live patterns sent for the bar playing now: [pattern row] -> data."
  []
  (let [bar (:bar @engine/playhead)
        sent (:sent @@#'live/state)]
    (into {} (keep (fn [[[p r b] data]] (when (= b bar) [[p r] data]))) sent)))
