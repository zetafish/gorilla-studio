(ns gorilla.wire
  "Frames on the brain/engine socket: a 4-byte big-endian length, then a
  MessagePack map. Keywords travel as strings holding their full name; on the
  way back, map keys and `:op` values become keywords again."
  (:require [gorilla.proto-schema :as proto])
  (:import (java.io DataInputStream OutputStream)
           (java.nio ByteBuffer)
           (org.msgpack.core MessagePack MessagePacker)
           (org.msgpack.value Value)))

(defn- pack! [^MessagePacker p x]
  (cond
    (map? x) (do (.packMapHeader p (count x))
                 (doseq [[k v] x] (pack! p k) (pack! p v)))
    (sequential? x) (do (.packArrayHeader p (count x))
                        (doseq [v x] (pack! p v)))
    (keyword? x) (.packString p (subs (str x) 1))
    (string? x) (.packString p ^String x)
    (int? x) (.packLong p (long x))
    (double? x) (.packDouble p (double x))
    (boolean? x) (.packBoolean p (boolean x))
    (nil? x) (.packNil p)
    :else (throw (ex-info (str "Cannot encode " (type x)) {:value x}))))

(defn- value->clj [^Value v]
  (cond
    (.isMapValue v) (into {}
                          (map (fn [[k x]]
                                 (let [k (keyword (value->clj k))
                                       x (value->clj x)]
                                   [k (if (and (= k :op) (string? x)) (keyword x) x)])))
                          (.map (.asMapValue v)))
    (.isArrayValue v) (mapv value->clj (.list (.asArrayValue v)))
    (.isStringValue v) (.asString (.asStringValue v))
    (.isIntegerValue v) (.asLong (.asIntegerValue v))
    (.isBooleanValue v) (.getBoolean (.asBooleanValue v))
    (.isFloatValue v) (.toDouble (.asFloatValue v))
    (.isNilValue v) nil
    :else (throw (ex-info "Unsupported MessagePack value" {:type (str (.getValueType v))}))))

(defn encode
  "Encodes `x` as a complete frame, header included."
  ^bytes [x]
  (let [packer (MessagePack/newDefaultBufferPacker)
        _ (pack! packer x)
        payload (.toByteArray packer)
        n (alength payload)]
    (when (> n proto/max-frame-bytes)
      (throw (ex-info "Frame too large" {:bytes n :max proto/max-frame-bytes})))
    (-> (ByteBuffer/allocate (+ 4 n))
        (.putInt n)
        (.put payload)
        (.array))))

(defn decode-payload
  "Decodes a frame payload (without its header)."
  [^bytes payload]
  (value->clj (.unpackValue (MessagePack/newDefaultUnpacker payload))))

(defn read-frame
  "Blocks for the next frame on `in` and decodes it. Throws on EOF, on an
  oversized length and on malformed MessagePack."
  [^DataInputStream in]
  (let [n (.readInt in)]
    (when-not (<= 0 n proto/max-frame-bytes)
      (throw (ex-info "Frame too large" {:bytes n :max proto/max-frame-bytes})))
    (let [buf (byte-array n)]
      (.readFully in buf)
      (decode-payload buf))))

(defn write-frame! [^OutputStream out ^bytes frame]
  (.write out frame)
  (.flush out))
