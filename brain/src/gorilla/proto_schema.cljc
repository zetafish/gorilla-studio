;; GENERATED from engine/proto by `cargo run -p gorilla-proto --bin gen-malli`.
;; Do not edit. Change the Rust types and regenerate.
(ns gorilla.proto-schema)

(def max-frame-bytes 65536)

(def pattern-steps 16)

(def max-clips 4096)

(def Gen
  [:multi {:dispatch :type}
   [:builtin
    [:map {:closed true}
     [:type [:= :builtin]]
     [:voice [:enum :kick :snare :hat :bass :lead]]]]
   [:sampler
    [:map {:closed true}
     [:type [:= :sampler]]
     [:sample :string]]]])

(def Note
  [:map {:closed true}
   [:t [:int {:min 0 :max 15}]]
   [:len [:int {:min 1 :max 64}]]
   [:pitch [:int {:min 0 :max 127}]]
   [:vel [:double {:min 0.0 :max 1.0}]]])

(def Notes [:vector {:max 16} Note])

(def Clip
  [:map {:closed true}
   [:pattern :keyword]
   [:start [:int {:min 0}]]
   [:len [:int {:min 1}]]])

(def Patch
  [:multi {:dispatch :op}
   [:pattern/set-steps
    [:map {:closed true}
     [:op [:= :pattern/set-steps]]
     [:pattern :keyword]
     [:row :keyword]
     [:steps [:vector {:min 16 :max 16} [:enum 0 1]]]]]
   [:pattern/schedule-bar
    [:map {:closed true}
     [:op [:= :pattern/schedule-bar]]
     [:pattern :keyword]
     [:row :keyword]
     [:bar [:int {:min 0}]]
     [:steps [:vector {:min 16 :max 16} [:enum 0 1]]]]]
   [:pattern/set-notes
    [:map {:closed true}
     [:op [:= :pattern/set-notes]]
     [:pattern :keyword]
     [:row :keyword]
     [:notes Notes]]]
   [:pattern/schedule-notes
    [:map {:closed true}
     [:op [:= :pattern/schedule-notes]]
     [:pattern :keyword]
     [:row :keyword]
     [:bar [:int {:min 0}]]
     [:notes Notes]]]
   [:transport/set
    [:map {:closed true}
     [:op [:= :transport/set]]
     [:playing :boolean]]]
   [:pattern/remove-row
    [:map {:closed true}
     [:op [:= :pattern/remove-row]]
     [:pattern :keyword]
     [:row :keyword]]]
   [:pattern/remove
    [:map {:closed true}
     [:op [:= :pattern/remove]]
     [:pattern :keyword]]]
   [:channel/set
    [:map {:closed true}
     [:op [:= :channel/set]]
     [:channel :keyword]
     [:generator Gen]]]
   [:channel/remove
    [:map {:closed true}
     [:op [:= :channel/remove]]
     [:channel :keyword]]]
   [:song/set-bpm
    [:map {:closed true}
     [:op [:= :song/set-bpm]]
     [:bpm [:double {:min 20.0 :max 999.0}]]]]
   [:song/set-playlist
    [:map {:closed true}
     [:op [:= :song/set-playlist]]
     [:clips [:vector {:max 4096} Clip]]
     [:looping :boolean]]]
   [:song/reset
    [:map {:closed true}
     [:op [:= :song/reset]]]]])

(def Envelope
  [:map {:closed true}
   [:epoch [:int {:min 0}]]
   [:ops [:vector Patch]]])

(def Telemetry
  [:multi {:dispatch :op}
   [:engine/hello
    [:map {:closed true}
     [:op [:= :engine/hello]]
     [:engine-id [:int {:min 0 :max 4294967295}]]
     [:epoch [:int {:min 0}]]]]
   [:engine/playhead
    [:map {:closed true}
     [:op [:= :engine/playhead]]
     [:epoch [:int {:min 0}]]
     [:playing :boolean]
     [:bar [:int {:min 0}]]
     [:step [:int {:min 0 :max 15}]]]]
   [:engine/rejected
    [:map {:closed true}
     [:op [:= :engine/rejected]]
     [:epoch [:int {:min 0}]]
     [:reason :string]
     [:ops [:vector :string]]]]])
