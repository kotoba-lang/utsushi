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

## R0（実装済み — 最初の E2E が green）

**MP4 demux → packet を content-id(CID) 化 → 再エンコードなし trim/concat → remux** が
動作する。codec 実復号には触れない（opaque）。

- `utsushi.bytes` — big-endian の read/write プリミティブ（純 cljc）
- `utsushi.container` — ISO BMFF box ツリーのスキャナ + `leaf-payload`（mdat は境界のみ）
- `utsushi.demux` — stbl サンプルテーブル(stts/stsz/stsc/stco/co64/stss)を読み、各 sample の
  offset/size/pts/keyframe を復元。sample bytes は `utsushi.blob/content-id` で参照化。
  stsd(codec config) は opaque な raw bytes として保持
- `utsushi.mux` — 最小 MP4 ライタ（`ftyp+mdat+moov` の mdat-first で chunk offset を一発計算、
  stsd verbatim 再emit）
- `utsushi.remux` — `trim`（pts 窓で sample 選択）/ `concat-streams`（track 連結 + pts シフト）
- `utsushi.blob` — content-id（R0 placeholder = FNV-1a32。真の CID は Vault blake3）
- `grammar/mp4.edn` — kasane.decode 用の EDN 文法（kasane 立ち上がり次第こちらへ委譲）
- `utsushi.{bitstream,codec,graph,quads}` — 設計に沿った骨組み（bitstream/codec は R1 で実装）

**検証**: `test/utsushi/e2e_test.cljc` が mux→demux→(trim/concat)→remux→demux の round-trip
不変条件（sample 集合・pts・keyframe・cid・bytes 一致）を確認。外部メディアファイル/プレーヤー
不要。`bb -cp src:test -e '(require (quote utsushi.e2e-test)) (utsushi.e2e-test/run)'` → `:ok`。

## R1 + Pregel（実装済み — capability-gated codec + BSP filtergraph が green）

ADR §3/§4 を cljc で realize（kotoba `policy.rs`/`effects.rs`/fuel/Pregel の鏡写し）。

- `utsushi.policy` — deny-by-default capability + effect soundness(T2) + per-frame gas。
  `deny-all`/`grant`/`with-gas-limit`/`check-graph`
- `utsushi.codec` — R1 façade（実 decode/encode は native host word 境界。cljc は opaque）
- `utsushi.graph` — filtergraph をデータ（node `{:id :op :args :effect}` + edge）で構築
- `utsushi.pregel` — filtergraph を **BSP superstep で決定論実行**。`filtergraph-cid`
  （= kotoba graph_def_cid 相当）/ per-frame gas（fuel 相当）/ `transcode` の CID-MV メモ化

**検証**: `test/utsushi/pregel_test.cljc` が deny-by-default 拒否・under-declaration 拒否(T2)・
per-frame gas 会計・gas 上限 trap(fuel)・BSP 決定論・同一 input+graph のメモ化を確認 → `:ok`。

### 残るランタイム統合
- kotoba-runtime に native `media`/`codec` WIT interface（`bind_evm`/`bind_btc` パターン）+
  `kotoba-clj` の `CapClass::MediaDecode/Encode` + `:media-decode/:media-encode` effect を追加し、
  cljc façade を実 native host word に接続（真の DCT/動き補償等）。
- `utsushi.graph`/`utsushi.pregel` を kotoba `defgraph` + Pregel BSP に射影（現状は cljc 実現）。

## ライセンス

TBD。
