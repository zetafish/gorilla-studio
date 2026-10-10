;; GENERATED from engine/proto by `cargo run -p gorilla-proto --bin gen-malli`.
;; Do not edit. Change the Rust types and regenerate.
(ns gorilla.proto-schema)

(def max-frame-bytes 65536)

(def pattern-steps 16)

(def Patch
  [:multi {:dispatch :op}
   [:pattern/set-steps
    [:map {:closed true}
     [:op [:= :pattern/set-steps]]
     [:pattern :keyword]
     [:row :keyword]
     [:steps [:vector {:min 16 :max 16} [:enum 0 1]]]]]
   [:transport/set
    [:map {:closed true}
     [:op [:= :transport/set]]
     [:playing :boolean]]]])

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
     [:step [:int {:min 0 :max 15}]]]]])
