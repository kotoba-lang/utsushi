# utsushi（映し）

ffmpeg 的な **時間ベースメディア（動画/音声）** を扱う純 cljc + EDN ライブラリ。
`kasane`（重ね = 静的グラフィック PSD/AI/PDF）の姉妹で、`utsushi` は「フレームの映し
（映像）」を扱う。

**設計 SSoT**: [`90-docs/adr/2606272200-utsushi-video-edn-filtergraph.md`](https://github.com/com-junkawasaki/orgs)（ADR-2606272200）

## 方針 — libavcodec を `.kotoba` で書き直さない

kotoba が EVM/BTC で確立した「read+verify surface だけ言語に出し、重い native は
capability-gated host import に隔離する」哲学と、kasane の「純 cljc + EDN データ駆動」を、
メディアの層ごとに使い分けて合成する。

| 層 | 哲学 | 実体 |
|---|---|---|
| ① コンテナ demux/mux（MP4/MOV） | 純 cljc + EDN 文法 | [`org-iso-isobmff`](https://github.com/kotoba-lang/org-iso-isobmff)（外部repo、依存） |
| ② ビットストリーム・フレーミング | 純 cljc（ヘッダのみ） | [`org-iso-h264`](https://github.com/kotoba-lang/org-iso-h264) / [`org-iso-aac`](https://github.com/kotoba-lang/org-iso-aac) / [`org-ietf-opus`](https://github.com/kotoba-lang/org-ietf-opus)（外部repo、未配線） |
| ③ codec カーネル（実 decode/encode） | R0 opaque / R1 capability-gated native | `utsushi.codec`（本repo） |
| ④ filtergraph | kotoba `defgraph` 射影 | `utsushi.graph`/`utsushi.pregel`/`utsushi.policy`（本repo） |
| 横断: フレーム = Vault blob + `media/*` datom（CID） | content-addressed | `utsushi.quads`（本repo） |

## 2026-07 分解: コンテナ/ビットストリーム仕様を reverse-domain repoへ抽出

`utsushi` はもともと ISOBMFF(MP4) の box-tree demux/mux/remux 実装を**それ自体で**
抱えていたが、これは `kasane.isobmff`（AVIF/HEIC メタ読取）と**同一仕様(ISO/IEC
14496-12)の重複実装**だった。kotoba-lang の既存命名規約（`org-<標準化団体>-<spec>`
reverse-domain 命名）に揃えるバッチ（`com-junkawasaki/root` ADR 前例2607072500 と
同型）で、両方を統合した [`org-iso-isobmff`](https://github.com/kotoba-lang/org-iso-isobmff)
へ抽出した — `utsushi` はそれに依存する **filtergraph オーケストレータ**になる。

`utsushi.bitstream`（NAL分割/SPS/ADTS framing）は調査の結果**全関数が未実装の
TODO stub**だったと判明。H.264/AAC/Opus はそれぞれ独立した外部仕様なので、TODOを
埋める形で新規に [`org-iso-h264`](https://github.com/kotoba-lang/org-iso-h264) /
[`org-iso-aac`](https://github.com/kotoba-lang/org-iso-aac) /
[`org-ietf-opus`](https://github.com/kotoba-lang/org-ietf-opus) として実装した
（`utsushi.bitstream` 自体は削除 — **本repoからの配線は follow-up**）。

`utsushi` に残るのは: capability/effect/gas モデル（kotoba `policy.rs`/`effects.rs`/
fuel の鏡写し）、filtergraph データ構築、BSP superstep 実行という **utsushi 固有の
知的財産**のみ。コンテナ/コーデック仕様の実装は各 reverse-domain repo に委ねる。

## R0（実装済み — E2E は org-iso-isobmff 側、本repoは依存のみ）

**MP4 demux → packet を content-id(CID) 化 → 再エンコードなし trim/concat → remux** の
実装・E2Eテストは [`org-iso-isobmff`](https://github.com/kotoba-lang/org-iso-isobmff) に
ある。`utsushi` はそれを `deps.edn` の git 依存として消費する。

## R1 + Pregel（実装済み — capability-gated codec + BSP filtergraph が green）

ADR §3/§4 を cljc で realize（kotoba `policy.rs`/`effects.rs`/fuel/Pregel の鏡写し）。

- `utsushi.policy` — deny-by-default capability + effect soundness(T2) + per-frame gas。
  `deny-all`/`grant`/`with-gas-limit`/`check-graph`
- `utsushi.codec` — R1 façade（実 decode/encode は native host word 境界。cljc は opaque）
- `utsushi.graph` — filtergraph をデータ（node `{:id :op :args :effect}` + edge）で構築
- `utsushi.pregel` — filtergraph を **BSP superstep で決定論実行**。`filtergraph-cid`
  （= kotoba graph_def_cid 相当）/ per-frame gas（fuel 相当）/ `transcode` の CID-MV メモ化。
  demux/remux/mux オペレータは `org-iso-isobmff` に委譲。

**検証**: `test/utsushi/pregel_test.cljc`（`clojure -M:test`、6 tests / 16 assertions）が
deny-by-default 拒否・under-declaration 拒否(T2)・per-frame gas 会計・gas 上限 trap(fuel)・
BSP 決定論・同一 input+graph のメモ化を、`org-iso-isobmff` 実物への git 依存越しに確認。

### 残るランタイム統合

- kotoba-runtime に native `media`/`codec` WIT interface（`bind_evm`/`bind_btc` パターン）+
  `kotoba-clj` の `CapClass::MediaDecode/Encode` + `:media-decode/:media-encode` effect を追加し、
  cljc façade を実 native host word に接続（真の DCT/動き補償等）。
- `utsushi.graph`/`utsushi.pregel` を kotoba `defgraph` + Pregel BSP に射影（現状は cljc 実現）。
- `org-iso-h264`/`org-iso-aac`/`org-ietf-opus` のビットストリーム framing を
  filtergraph の `:decode` node 前段に配線（現状 utsushi からは未参照）。

## テスト

```sh
clojure -M:test
```

## ライセンス

TBD。
