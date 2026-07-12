(ns utsushi.codec.store
  "Datomic-backed decode-state Store for R0.5 pixel decode (ADR-2607122000
   §4, Migration手順5).

  R0.5 (`utsushi.codec`'s sibling namespaces + `org-iso-h264`'s
  `h264.decode/decode-idr-frame`) can now actually reconstruct pixels from a
  real H.264 baseline elementary stream — but that decode is a single, in-
  memory, synchronous call with no notion of a persisted job, GOP structure,
  or reference-frame graph. This namespace supplies exactly that persistence
  layer, reusing the SAME `:db-api` injection pattern as the three existing
  actors (robotaxi-actor / gftd-talent-actor / cloud-itonami) and
  `langgraph.checkpoint/datomic-checkpointer` — no new abstraction is
  invented here.

  ## Store abstraction (not invented here — see references)

  `db-api` is the `{:q :transact! :db :pull :entid}` map from
  `langchain.db/api` (a pure .cljc, Datomic-API-compatible in-memory EAV
  store) or a Datomic/DataScript-shaped map providing the same keys (e.g.
  `langchain.kotoba-db/kotoba-api`, XRPC → a real Datomic backend). Callers
  inject it via `{:keys [db-api] :or {db-api db/api}}`, exactly like
  `langgraph.checkpoint/datomic-checkpointer`.

  ## Schema (`decode-schema`)

  Attribute naming follows `langgraph.checkpoint/checkpoint-schema`'s
  convention: a `:decode/job` identity key + a flat namespaced-keyword
  attribute map, compound values stored as `pr-str` EDN (same technique
  `checkpoint-schema` uses for `:checkpoint/state`/`:checkpoint/frontier`).

  - `:decode/job`        — job id (string), `:db.unique/identity` key.
  - `:decode/codec`      — `:h264` / `:av1` (only `:h264` is wired; see
                           `decode-frame!`).
  - `:decode/frame-num`  — this job's frame number within its stream.
  - `:decode/poc`        — picture order count. `h264.decode/decode-idr-frame`
                           only decodes a single IDR I-slice per call and does
                           not itself track POC/frame-num bookkeeping across
                           calls (see its own docstring's scope note), so
                           `decode-frame!` accepts `:frame-num`/`:poc` as
                           caller-supplied options (defaulting to 0, correct
                           for the IDR-only case this repo's golden vectors
                           cover) rather than deriving them — deriving POC
                           from `slice_header`'s `pic_order_cnt_lsb` across a
                           real multi-frame GOP is `h264.slice`/`h264.decode`
                           scope, not this Store layer's.
  - `:decode/gop`        — GOP structure, `pr-str` EDN. For this Phase-1
                           IDR-only wiring this is always a single-frame GOP
                           descriptor (`{:idr-frame-num <n>}`) — real GOP
                           structures (open/closed GOP, B-frame reordering)
                           are out of scope until `h264.decode` grows
                           inter-prediction (ADR-2607122000 Migration手順7).
  - `:decode/ref-frames` — `:db.type/ref` `:db.cardinality/many` — other
                           `:decode/job` entities this frame was predicted
                           from. Always empty for IDR frames (no inter
                           prediction yet); wired for future P/B-slice
                           decode to attach without a schema change.
  - `:decode/mv-field`   — motion-vector field, `pr-str` EDN. Always nil for
                           this intra-only decoder (no motion vectors exist
                           to persist yet — `codec-primitives`' `{:poc
                           :ref-idx :mv [dx dy] :ref-frame}` shape is the
                           target representation once inter-prediction
                           lands).
  - `:decode/frame-blob` — the reconstructed frame. **Scope note**: the ADR
                           text's ideal is a Vault blob CID reference, but
                           `utsushi` has no Vault blob integration wired yet
                           (its own CLAUDE.md's \"media 実体は Vault blob +
                           CID 参照のみ\" invariant is aspirational, not yet
                           implemented anywhere in this repo — `utsushi.quads`
                           documents the target shape but nothing populates
                           it). Given that, this stores `(pr-str {:width
                           :height :luma})` — the SAME EDN-blob-in-an-
                           attribute technique `checkpoint-schema` already
                           uses for `:checkpoint/state` — so the actual
                           decoded pixels round-trip through the Store
                           losslessly and can be asserted against a golden
                           vector in a test. Swapping this for a real Vault
                           blob CID later is a `decode-frame!` internal
                           change, not a schema/attribute-shape change (the
                           attribute keeps meaning \"the frame\", however
                           it's addressed).
  - `:decode/status`     — `:pending` → `:decoded` | `:failed`.

  `:decode/error` (not in the ADR's schema sketch, added here) carries
  `(ex-message e)` when `:decode/status` is `:failed`, for callers that want
  to see why without re-running the decode — a small, additive, backward-
  compatible extension in the same spirit as `checkpoint-schema`'s own
  attributes (nothing reads it structurally; it's purely diagnostic)."
  (:require [langchain.db :as db]
            [h264.decode :as h264-decode]
            #?(:clj [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])))

(def decode-schema
  "Merge into your db schema (same convention as
   `langgraph.checkpoint/checkpoint-schema`)."
  {:decode/job        {:db/unique :db.unique/identity}
   :decode/codec      {}
   :decode/frame-num  {}
   :decode/poc        {}
   :decode/gop        {}                                     ; pr-str EDN
   :decode/ref-frames {:db/valueType :db.type/ref
                       :db/cardinality :db.cardinality/many}
   :decode/mv-field   {}                                      ; pr-str EDN, nil for intra-only
   :decode/frame-blob {}                                      ; pr-str EDN {:width :height :luma}
   :decode/status     {}
   :decode/error      {}})

(defn- ex-msg [e]
  #?(:clj (.getMessage ^Throwable e)
     :cljs (.-message e)
     :default (str e)))

(defn job-entity
  "Pulls the current `:decode/job` entity for `job-id` (or nil), decoding
   the `pr-str`'d attributes back into EDN so callers don't have to."
  [conn job-id {:keys [db-api] :or {db-api db/api}}]
  (let [{:keys [db pull]} db-api
        m (pull (db conn) '[*] [:decode/job job-id])]
    (when (:decode/job m)
      (cond-> m
        (:decode/gop m)        (update :decode/gop edn/read-string)
        (:decode/mv-field m)   (update :decode/mv-field edn/read-string)
        (:decode/frame-blob m) (update :decode/frame-blob edn/read-string)))))

(defn decode-frame!
  "Orchestrates one H.264 baseline IDR-I-slice pixel decode
   (`h264.decode/decode-idr-frame`) through a persisted `:decode/job`,
   exactly like ADR-2607122000 §4/Migration手順5 describes:

     1. transact `:decode/job` with `:decode/status :pending`
     2. call the real org-iso-h264 decoder on `annexb-bytes`
     3. on success: transact `:decode/status :decoded` +
        `:decode/frame-blob` (the reconstructed `{:width :height :luma}`) +
        `:decode/frame-num`/`:decode/poc` (0/0 by default — correct for a
        standalone IDR frame; see `decode-schema` docstring for why POC
        derivation across a GOP is out of scope here)
     4. on failure: transact `:decode/status :failed` (+ `:decode/error`)
        and re-throw (callers that only want the persisted status without
        an exception should catch around this call, or use `job-entity`
        after catching)

   `conn` is whatever `db-api`'s `:transact!`/`:db`/`:pull` expect (a
   `langchain.db/create-conn` atom by default). Returns the job entity map
   on success (via `job-entity`).

   Options: `:db-api` (default `langchain.db/api`), `:codec` (default
   `:h264` — the only codec `h264.decode` supports), `:frame-num`/`:poc`
   (default 0)."
  ([conn job-id annexb-bytes] (decode-frame! conn job-id annexb-bytes {}))
  ([conn job-id annexb-bytes
    {:keys [db-api codec frame-num poc]
     :or {db-api db/api codec :h264 frame-num 0 poc 0}}]
   (when-not (= codec :h264)
     (throw (ex-info "utsushi.codec.store/decode-frame!: only :h264 is wired to a real decoder"
                      {:codec codec})))
   (let [{:keys [transact!]} db-api]
     (transact! conn [{:db/id job-id
                        :decode/job job-id
                        :decode/codec codec
                        :decode/status :pending}])
     (try
       (let [{:keys [width height luma]} (h264-decode/decode-idr-frame annexb-bytes)]
         (transact! conn [{:db/id job-id
                            :decode/job job-id
                            :decode/status :decoded
                            :decode/frame-num frame-num
                            :decode/poc poc
                            :decode/gop (pr-str {:idr-frame-num frame-num})
                            :decode/frame-blob (pr-str {:width width :height height :luma luma})}])
         (job-entity conn job-id {:db-api db-api}))
       (catch #?(:clj Exception :cljs :default) e
         (transact! conn [{:db/id job-id
                            :decode/job job-id
                            :decode/status :failed
                            :decode/error (ex-msg e)}])
         (throw e))))))
