# utsushi（映し）

ffmpeg 的な **時間ベースメディア（動画/音声）** を扱う純 cljc + EDN ライブラリ。
`kasane`（重ね = 静的グラフィック PSD/AI/PDF）の姉妹で、`utsushi` は「フレームの映し
（映像）」を扱う。

**設計 SSoT**: [`90-docs/adr/2606272200-utsushi-video-edn-filtergraph.md`](https://github.com/com-junkawasaki/orgs)（ADR-2606272200）

## 方針 — libavcodec を `.kotoba` で書き直さない

kotoba が EVM/BTC で確立した「read+verify surface だけ言語に出し、重い native は
capability-gated host import に隔離する」哲学と、kasane の「純 cljc + EDN データ駆動」を、
メディアの層ごとに使い分けて合成する。

| 層 | 哲学 | 外部依存 |
|---|---|---|
| ① コンテナ demux/mux（MP4/MKV/TS…） | 純 cljc + EDN 文法（→ kasane.decode） | ゼロ |
| ② ビットストリーム・フレーミング | 純 cljc（ヘッダのみ） | ゼロ |
| ③ codec カーネル（実 decode/encode） | R0 opaque / R1 capability-gated native | R1 のみ |
| ④ filtergraph | kotoba `defgraph` 射影 | ゼロ |
| 横断: フレーム = Vault blob + `media/*` datom（CID） | content-addressed | ゼロ |

## なぜ安全か（kotoba safe-clj 上）

- **fuel/gas** — 細工 stream で host をハングできない
- **deny-by-default capability** — codec 権限が ambient に湧かない（`Policy::deny_all`）
- **effect soundness (T2)** — 「純フィルタ」が裏で fs/net に出れば**コンパイルが落ちる**
- **content-addressing** — 同一入力 + 同一 filtergraph → 同一出力 CID で自動メモ化

## R0（現状）

- `utsushi.container` — ISO BMFF box ツリーの最小スキャナ（mdat は境界のみ）
- `grammar/mp4.edn` — kasane.decode 用の EDN 文法（立ち上がり次第委譲）
- `utsushi.{bitstream,codec,graph,quads}` — 設計に沿った骨組み（多くは TODO）

R0 の最初の E2E ゴール: **MP4 demux → packet を blob(CID) 化 → 再エンコードなし trim/concat
→ remux**。codec 実復号は R1（capability-gated native host word）。

## ライセンス

TBD。
