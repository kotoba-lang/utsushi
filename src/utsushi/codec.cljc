(ns utsushi.codec
  "③ codec カーネル層 — R1 façade。

  設計: ADR-2606272200 §3。実 decode/encode（DCT/動き補償/CABAC/AV1）は純 cljc では framerate
  で回らない（SIMD/threads 無し・fuel・streaming 不可）。よって実体は **capability-gated native
  host word**（kotoba-runtime の新規 `media`/`codec` WIT interface, `bind_evm`/`bind_btc` パターン）。

  cljc 側はその native 境界を表現する façade:
   - capability/effect の検査と per-frame gas 会計は utsushi.policy + utsushi.pregel が担う。
   - ここでは『native を呼んだ』という stage 遷移のみ行い、bytes は opaque passthrough する
     （真の変換は native host word。R1 cljc では opaque）。"
  (:require [utsushi.blob :as blob]))

(defn frame-count
  "demux 構造の総 sample(frame) 数。per-frame gas 会計に使う。"
  [demuxed]
  (reduce + 0 (map #(count (:samples %)) (:tracks demuxed))))

(defn decode
  "native decode 境界（R1: opaque passthrough）。:stage を :decoded にする。"
  [_policy _codec demuxed]
  (assoc demuxed :stage :decoded))

(defn encode
  "native encode 境界（R1: opaque passthrough）。:stage を :encoded にする。"
  [_policy _codec _opts demuxed]
  (assoc demuxed :stage :encoded))
