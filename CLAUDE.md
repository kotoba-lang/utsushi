# utsushi（映し）

時間ベースメディア（動画/音声）の純 cljc + EDN ライブラリ。`kasane`（静的グラフィック）の
姉妹。**設計 SSoT は superproject の ADR-2606272200**
(`90-docs/adr/2606272200-utsushi-video-edn-filtergraph.md`)。

## 不変条件（破ってはいけない）

- **libavcodec を `.kotoba`/cljc で再実装しない。** codec 内側ループ（DCT/動き補償/CABAC/AV1）は
  純 cljc では framerate で回らない（SIMD/threads 無し・fuel per-instruction・streaming 不可・
  byte API は write-only builder）。codec は R0 opaque / R1 capability-gated native host word。
- **メディア実体（mdat/フレーム/パケット）を EDN・git にインラインしない。** Vault blob + CID
  参照のみ（CLAUDE.md 大容量バイナリ規律 / ADR §5）。生フレームを言語境界 `list<u8>` で跨がせない。
- **層の哲学を混ぜない。** ①②④ は純 cljc + EDN（外部依存ゼロ）。③ R1 のみ capability で隔離した
  native（CapClass::MediaDecode/MediaEncode + :media-decode/:media-encode effect）。

## 層

| ns | 役割 |
|---|---|
| `utsushi.container` | ① ISO BMFF box ツリー（R0 手書き → kasane.decode + grammar/mp4.edn） |
| `utsushi.bitstream` | ② NAL/SPS/ADTS のフレーミング・メタ（ヘッダのみ） |
| `utsushi.codec`     | ③ R0 opaque passthrough / R1 native host word façade |
| `utsushi.graph`     | ④ filtergraph を EDN で組み立て kotoba defgraph へ射影 |
| `utsushi.quads`     | フレーム/パケット/メタ → `media/*` Datom 射影（CID 参照） |

## R0 ゴール

MP4 demux → packet を blob(CID) 化 → 再エンコードなし trim/concat → remux → `media/*` 射影。
