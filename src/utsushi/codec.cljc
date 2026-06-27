(ns utsushi.codec
  "③ codec カーネル層 — 実 decode/encode。

  設計: ADR-2606272200 §3。純 cljc では framerate で回らない（SIMD/threads 無し・fuel
  per-instruction・streaming 不可・byte API は write-only builder）。よって 2 段構え:

    R0: opaque — decode/encode を行わず blob(CID) を passthrough。demux/remux/trim/
        concat/メタ/字幕はコンテナ層で完結するので R0 でも実用になる。
    R1: capability-gated native host word — kotoba-runtime/wit/world.wit の `kotoba-node`
        world に新規 `media`/`codec` interface を足し、evm/btc/egress と同列に bind。
        pure-Rust decoder もしくは WGSL(kotoba-llm wgpu)。safe-clj 側は
        CapClass::MediaDecode/MediaEncode + :media-decode/:media-encode effect + per-frame
        gas 会計 + fs/net allowlist(既存 egress.fetch を配線)で隔離する。

  本 ns は R0 の opaque passthrough と、R1 host word を呼ぶ薄い façade を定義する。")

(defn decode
  "R0: opaque。codec/packet-cid をそのまま frames として通す（実復号なし）。
  R1: native host word `media/decode` を呼ぶ（capability `:media-decode` が必要）。"
  [_codec packets]
  {:utsushi/opaque true :packets packets})

(defn encode
  "R0: opaque passthrough。R1: native host word `media/encode`（`:media-encode`）。"
  [_codec _opts frames]
  {:utsushi/opaque true :frames frames})
