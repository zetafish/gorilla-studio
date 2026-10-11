(ns gorilla.ui.snapshot
  "Renders UI components without a window: into a Skia surface, saved as PNG
  if you like, with mouse events delivered straight to the component tree."
  (:require [io.github.humbleui.protocols :as protocols]
            [io.github.humbleui.ui :as ui]
            [io.github.humbleui.util :as util])
  (:import (io.github.humbleui.skija EncoderPNG Surface)))

(def scale 2)

(defn- ctx [] {:window nil :scale scale :mouse-pos (util/ipoint 0 0)})

(defn mount
  "A component tree ready to draw, `w` x `h` dip."
  [comp w h]
  {:node (ui/make [ui/default-theme {} [ui/focus-controller [ui/overlay-root comp]]])
   :w w :h h})

(defn draw!
  "Draws the tree; returns the Skia surface. Layout happens here, so draw
  before sending events."
  ^Surface [{:keys [node w h]}]
  (let [surface (Surface/makeRasterN32Premul (* scale w) (* scale h))
        canvas (.getCanvas surface)
        bounds (util/irect-xywh 0 0 (* scale w) (* scale h))]
    (protocols/-draw node (ctx) bounds (util/ipoint (* scale w) (* scale h)) bounds canvas)
    surface))

(defn png!
  "Draws and writes a PNG."
  [mounted file]
  (let [surface (draw! mounted)
        data (EncoderPNG/encode (.makeImageSnapshot surface))]
    (with-open [out (java.io.FileOutputStream. (str file))]
      (.write out (.getBytes data)))
    file))

(defn click!
  "A primary-button press and release at (x, y) in dip."
  [{:keys [node]} x y]
  (let [x (* scale x) y (* scale y)]
    (doseq [pressed? [true false]]
      (protocols/-event node (ctx) {:event :mouse-move :x x :y y})
      (protocols/-event node (ctx) {:event :mouse-button :button :primary :pressed? pressed?
                                    :x x :y y :modifiers #{}}))))

(defn unmount! [{:keys [node]}]
  (protocols/-unmount node))
