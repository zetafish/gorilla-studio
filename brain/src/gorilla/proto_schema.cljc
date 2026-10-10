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
     [:steps [:vector {:min 16 :max 16} [:enum 0 1]]]]]])

(def Envelope
  [:map {:closed true}
   [:epoch [:int {:min 0}]]
   [:ops [:vector Patch]]])
