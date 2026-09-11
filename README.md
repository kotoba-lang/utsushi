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
（`utsushi.bitstream` 自体は削除）。

> **2026-08-29 訂正**: ここには長く「**本repoからの配線は follow-up**」と書いて
> あったが、**それは 2026-07-08 の時点の記述で、その後 stale になっていた**。
> H.264 の配線は既に在る — `utsushi.pipeline.mp4-h264` が MP4 → demux → avcC →
> Annex B → 実画素 decode を、`utsushi.pipeline.h264-mp4` が逆方向を、
> `utsushi.pipeline.remux` が再エンコードなしの trim/concat を持ち、いずれも
> **実 ffmpeg のゴールデンベクタで bit-exact 検証済み**（下記）。
> 未配線のまま残っているのは AAC/Opus だけで、その理由は本文最後に書いてある
> とおり「demux 済み sample には適用対象が無い」という設計判断であって
> follow-up ではない。

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

**検証**: `test/utsushi/pregel_test.cljk`（`clojure -M:test`、6 tests / 16 assertions）が
deny-by-default 拒否・under-declaration 拒否(T2)・per-frame gas 会計・gas 上限 trap(fuel)・
BSP 決定論・同一 input+graph のメモ化を、`org-iso-isobmff` 実物への git 依存越しに確認。

## H.264 ビットストリーム配線（2026-07-08 decode、2026-07-12 encode、`org-iso-h264` 実配線済み）

`utsushi.codec/decode` は `codec=:h264` のとき、track の `stsd`（isobmff.demux が
opaque raw bytes のまま保持している box）から **avcC(AVCDecoderConfigurationRecord,
ISO/IEC 14496-15)** を読み、埋め込まれた SPS NAL Unit を `org-iso-h264` の
`h264.rbsp/unescape` + `h264.sps/parse` に通して実際の width/height/profile-idc を
取得し、各 video track に `:params` として付与する。**実 libx264 エンコード済み
MP4(96x64, baseline)で検証済み**（`test/utsushi/codec_test.cljk`）。

`utsushi.codec/encode` は decode の鏡像: `codec=:h264` のとき、video track の
`:params`（decode が生成するのと同じ形 — width/height/profile-idc/level-idc）から
`org-iso-h264` の `h264.sps/encode` + `h264.pps/encode` + `h264.rbsp/escape` で
実際に SPS+PPS NAL Unit を合成し、avcC 埋め込みの `:stsd` を組み立てて track に
付与する。**org-iso-h264 自身のスコープ限定（パラメータセット層のみ、
macroblock/pixel/CAVLC/CABAC は未実装）をそのまま継承** — フレームデータ
（`:samples`）は合成しない。`encode` → `decode` の round trip でパラメータが
完全に一致することを確認済み（`test/utsushi/codec_encode_test.cljk`、
`h264-encode-decode-round-trip`）。

**AAC(`org-iso-aac`)/Opus(`org-ietf-opus`)は意図的に未配線**: MP4 の
`mp4a`/`Opus` サンプルエントリは ESDS/dOps という ADTS/Opus TOC とは別の
config記述形式を使い、`isobmff.demux` が返す各 sample は**既に1個の生
アクセスユニット/packetの完全な境界**（ADTSヘッダやOpus TOCバイトを持たない）。
`org-iso-aac`/`org-ietf-opus` はどちらも「連結された生ストリームから境界を
割り出す」ためのものであり、demux 済みsampleに対しては適用対象そのものが
存在しない（誤適用すると先頭バイトをADTS/TOCと誤認してデータ破壊する）。
これらのrepoが本来活きるのは、MP4を経由しない生エレメンタリストリーム取り込み
（`.aac`/`.opus`ファイル直接、RTP payload等）で、utsushi の filtergraph は
現状 `:demux` op がISOBMFFしか受け付けないためそのような入力経路自体が無い
（新規追加はこのfollow-upのスコープ外 — 新機能であって『配線』ではない、
詳細は `utsushi.codec` のdocstring参照）。

## 複数フレーム decode（GOP: IDR + P-frames、2026-08-29）

`utsushi.pipeline.mp4-h264/decode-h264-frames` は MP4 の **全 sample** を 1 本の
Annex B ストリーム（avcC の SPS + PPS、続いて各 sample の NAL unit）に組み立てて
`org-iso-h264` の `h264.decode/decode-gop` に渡し、**GOP 全体の復号フレーム列**を
返す。各 P-slice は直前の復号ピクチャを単一参照フレームにする。

- pin: `org-iso-h264` を `55ffcdd` → `9bff54f`（default branch tip、34 ahead /
  0 behind、merge-base == 旧 pin = 純 fast-forward）。`55ffcdd` に `decode-gop` は
  無く、1 枚目の IDR しか decode できなかった。
- 単一フレーム経路（`decode-first-h264-frame` → `decode-idr-frame`）はそのまま。

### 実エンコーダ出力に対して decode-gop が受けたもの・拒んだもの

**libx264 に Intra_4x4 を使うなと言う方法は無い。** `--partitions none` は
`analyse.inter` を 0 にするだけで、x264 自身が印字するパラメータ行は
`analyse=0x1:0` のまま — この `0x1` が `X264_ANALYSE_I4x4`（実測 2026-08-29、
ffmpeg 8.1.1 / x264 core 165 r3222）。したがって**ディテールのある内容は必ず
I_NxN マクロブロックを含み**、`org-iso-h264` はそれを名指しで拒否する:

```
h264.decode: only Intra_16x16 mb_type (1..24) is supported
{:mb-type 0 :reason "I_NxN (Intra_4x4/8x8) not implemented"}
```

境界の両側を fixture として持っている（`test/utsushi/pipeline/mp4_h264_gop_test.cljk`）:

| fixture | ffprobe | decode-gop |
|---|---|---|
| `mp4-h264-gop32x3.mp4` | I,P,P | **受理** — 3 フレームとも luma+Cb+Cr が実 ffmpeg と bit-exact。P-slice は P_L0_16x16 と CAVLC intra MB の混在 |
| `mp4-h264-skip32x3.mp4` | I,P,P | **受理** — 同上。P-slice は 100% P_Skip |
| `mp4-h264-i4x4-reject.mp4` | I,P,P | **拒否** — 上記の I_NxN。理由の literal ごと assert している |

**動きベクトルは全て `[0 0]`。** 平坦な内容だけが Intra_4x4 なしで符号化され、
平坦な内容には動かすものが無いため、`decode-gop` の sub-pel 動き補償
（`h264.interp`）は**実エンコーダ出力では一度も実行されていない**。これは実在の
穴であり、手書きビットストリームで埋めていない（それは decoder を「この repo 自身の
H.264 観」に対して検査することになる）。

## 再エンコードなし trim / concat（`utsushi.pipeline.remux`、2026-08-29）

`trim`（sample index `[start end)`）と `concat-mp4s` を MP4 bytes → MP4 bytes で
提供する。コンテナ変換そのものは `org-iso-isobmff`（`isobmff.remux`）に委ね、
utsushi は orchestration と sample-index 面だけを持つ。

**検証は画素で行い、コンテナのバイト列では行わない。** 同じピクチャを書く 2 つの
muxer は box 順・`tkhd` の width/height・padding・chunk offset で正当に食い違うので、
バイト比較は正しさと無関係な理由で落ちる。`remux_test.clj` は utsushi の trim /
concat 出力を decode して、**実 ffmpeg が自身の `-c copy` 編集を decode した画素**と
bit-exact 比較する（`ffmpeg -c copy -frames:v 2` / `ffmpeg -f concat -c copy`）。
逆方向 — 実 ffmpeg が utsushi の書いた MP4 を decode できること — も実測済み
（trim / concat とも ffmpeg の自前編集と画素一致、2026-08-29）。

IDR を落とす trim は well-formed だが decode できない。`sync-sample-indices` が
その開始点を答え、`decode-gop` が落ちたときは理由を名指しで返す。

## R0.5 pixel decode + Datomic decode-state Store（2026-07-12、ADR-2607122000 §4/Migration手順5）

ADR-2607122000（本ADRの前提ADR-2606272200への追補）が「R0.5」として `org-iso-h264` に
実際のH.264 baseline intra画素decoder（`h264.decode/decode-idr-frame`、CAVLC +
Intra_16x16、実libx264/ffmpegゴールデンベクタでbit-exact検証済み）を追加した。
`utsushi.codec.store`（本repo新規）はその decode 呼び出しを、既存3 actor
（robotaxi-actor/gftd-talent-actor/cloud-itonami）と同じ `:db-api` 注入パターンで
Datomic-backed Store に永続化するオーケストレーション層。新しい抽象は発明していない
— `langchain.db/api`（`{:q :transact! :db :pull :entid}`）と
`langgraph.checkpoint/checkpoint-schema`（namespace prefix + `pr-str` EDN の素朴な
attribute map）をそのまま流用する。

- **`decode-schema`** — `:decode/job`（`:db.unique/identity`）/ `:decode/codec` /
  `:decode/frame-num` / `:decode/poc` / `:decode/gop`(pr-str EDN) /
  `:decode/ref-frames`(`:db.type/ref` many、将来のinter-prediction用、IDRのみの現状は
  常に空) / `:decode/mv-field`(pr-str EDN、intra-onlyの現状は常にnil) /
  `:decode/frame-blob`(pr-str EDN `{:width :height :luma}`) / `:decode/status`
  （`:pending`→`:decoded`|`:failed`）/ `:decode/error`（診断用、ADRのスキーマ案には
  ない追加属性）。
- **`decode-frame!`** — `:pending` transact → 実際に `h264.decode/decode-idr-frame`
  呼び出し → 成功なら `:decoded` + frame-blob 等を transact、失敗なら `:failed` を
  transact して re-throw。
- **`:decode/frame-blob` のスコープ**: ADR本文の理想は Vault blob CID 参照だが、
  utsushi自身にまだVault blob統合が無い（`utsushi.quads` が目標shapeを記述するのみで
  実配線はゼロ）ため、`checkpoint-schema`の `:checkpoint/state` と同じ「pr-str EDN を
  attributeにそのまま入れる」技法で実装している——将来Vault blob統合が入れば
  `decode-frame!` 内部の変更で置き換わり、schema/属性の意味は変わらない。
- **テスト戦略**: `gftd-talent-actor`の`talent.store-contract-test`（MemStore/
  DatomicStoreの両方に対して同一contractを実行、ただしどちらも`langchain.db`
  ベースでin-process — 実Datomic接続はテストしない）にならい、
  `test/utsushi/codec_store_test.cljk` は `langchain.db/api`（既定の`db-api`）に
  対する統合テストのみを用意。実際の `org-iso-h264` golden vector
  （`flat16-dc-only.h264`、実libx264エンコード+実ffmpeg decode基準、本repoの
  `resources/utsushi/fixtures/`に複製）を1本decodeし、`:pending`→`:decoded`の
  状態遷移と、persistした`:decode/frame-blob`の画素がゴールデンベクタとbit-exact
  であることを検証する。実Datomicバックエンド（`langchain.kotoba-db`経由）に対する
  contract testは、3 actorの現状の慣習（MemStore同等のin-process検証のみ）を
  踏襲し、本follow-upのスコープには含めていない。

### 残るランタイム統合

- kotoba-runtime に native `media`/`codec` WIT interface（`bind_evm`/`bind_btc` パターン）+
  `kotoba-clj` の `CapClass::MediaDecode/Encode` + `:media-decode/:media-encode` effect を追加し、
  cljc façade を実 native host word に接続（真の DCT/動き補償等 — SPS読み取りの先、
  実画素decodeそのもの）。
- `utsushi.graph`/`utsushi.pregel` を kotoba `defgraph` + Pregel BSP に射影（現状は cljc 実現）。
- MP4を経由しない生エレメンタリストリーム取り込み経路（新機能）を追加すれば、
  その時初めて `org-iso-aac`/`org-ietf-opus` が活きる。

## テスト

```sh
clojure -M:test
```

## ライセンス

TBD。
