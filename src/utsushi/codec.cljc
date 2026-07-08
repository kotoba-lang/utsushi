(ns utsushi.codec
  "③ codec カーネル層 — R1 façade。

  設計: ADR-2606272200 §3。実 decode/encode（DCT/動き補償/CABAC/AV1）は純 cljc では framerate
  で回らない（SIMD/threads 無し・fuel・streaming 不可）。よって実体は **capability-gated native
  host word**（kotoba-runtime の新規 `media`/`codec` WIT interface, `bind_evm`/`bind_btc` パターン）。

  cljc 側はその native 境界を表現する façade:
   - capability/effect の検査と per-frame gas 会計は utsushi.policy + utsushi.pregel が担う。
   - ここでは『native を呼んだ』という stage 遷移のみ行い、bytes は opaque passthrough する
     （真の変換は native host word。R1 cljc では opaque）。
   コンテナ層（demux/mux/remux/bytes/blob）は org-iso-isobmff へ抽出済み
   （kotoba-lang reverse-domain media/graphics standards-substrate split,
   com-junkawasaki/root ADR 前例2607072500）。

  H.264/AAC/Opus のビットストリーム framing repo（org-iso-h264/org-iso-aac/
  org-ietf-opus）との配線: **H.264 だけ**が実際に配線対象になる。理由は
  h264-track-params のdocstring参照 — avcC は SPS/PPS NALUを埋め込んで
  持っており、org-iso-h264 の RBSP unescape + SPS parse がそのまま使える。
  AAC/Opus は事情が違う: MP4 の `mp4a`/`Opus` サンプルエントリは ESDS/dOps
  という**ADTSともOpus TOCとも異なる別のconfig記述形式**を使い、かつ
  isobmff.demux が返す各 sample は**既に1個の生アクセスユニット/packetの
  完全な境界**（ADTSヘッダやOpus TOCバイトを持たない）。org-iso-aac の
  ADTS parser・org-ietf-opus の TOC parser はどちらも「連結された生
  ストリームから境界を割り出す」ためのものであり、demux 済みsampleに
  対しては**適用対象そのものが存在しない**（誤って適用すると先頭バイトを
  ADTS/TOCと誤認してデータ破壊する）。これらのrepoが本来活きるのは、
  MP4を経由しない生エレメンタリストリーム取り込み（.aac/.opus ファイル
  直接、RTP payload等）で、utsushi の filtergraph は現状 :demux op が
  ISOBMFFしか受け付けないためそのような入力経路自体が無い（新規追加は
  この follow-up のスコープ外 — 新機能であって『配線』ではない）。"
  (:require [isobmff.blob :as blob]
            [isobmff.box :as box]
            [h264.rbsp :as rbsp]
            [h264.sps :as sps]))

(defn frame-count
  "demux 構造の総 sample(frame) 数。per-frame gas 会計に使う。"
  [demuxed]
  (reduce + 0 (map #(count (:samples %)) (:tracks demuxed))))

;; ---- H.264: avcC(AVCDecoderConfigurationRecord, ISO/IEC 14496-15) → SPS ----
;; org-iso-isobmff の demux は stsd を opaque raw bytes のまま保持する
;; （verbatim remux のため）。ここで初めて中身を覗き、avc1 サンプルエントリの
;; 固定ヘッダ(86byte = 8box header + 8 SampleEntry base + 70 VisualSampleEntry
;; 固有フィールド)をスキップして子box(avcC)を isobmff.box で読む。

(def ^:private visual-sample-entry-fixed-header 86)

(defn- find-avcc
  "stsd(raw bytes — isobmff.demuxはstsdの**外側box header込み**でsliceする点に
   注意: box header(8) + FullBox version/flags(4) + entry-count(4) = 16byte
   の後にサンプルエントリが1つ以上続く) から avcC box の payload を返す
   （無ければ nil）。"
  [stsd-bytes]
  (let [buf (vec stsd-bytes)
        entry-start 16                                    ; stsd box header(8) + FullBox(4) + entry-count(4)
        sample-entries (box/parse-boxes buf entry-start (count buf))
        entry (first sample-entries)]                      ; R0: 最初のsample entryのみ見る
    (when entry
      (let [children (box/parse-boxes buf
                                       (+ (:start entry) visual-sample-entry-fixed-header)
                                       (+ (:start entry) (:size entry)))
            avcc (box/find-box children "avcC")]
        (when avcc (box/leaf-payload buf avcc))))))

(defn h264-track-params
  "track の :stsd（raw box bytes）から avcC 経由で最初の SPS を取り出し、
   org-iso-h264(h264.rbsp/h264.sps) で解析する。→ {:width :height
   :profile-idc :level-idc} か、avcC/SPS が無ければ nil。"
  [track]
  (when-let [avcc (find-avcc (:stsd track))]
    (let [buf        (vec avcc)
          num-sps    (bit-and (nth buf 5) 0x1F)            ; byte5: reserved(3)+numSPS(5)
          sps-len    (bit-or (bit-shift-left (nth buf 6) 8) (nth buf 7))
          sps-nalu   (subvec buf 8 (+ 8 sps-len))]
      (when (pos? num-sps)
        (sps/parse (rbsp/unescape sps-nalu))))))

(defn decode
  "native decode 境界。R1: bytes は opaque passthrough（:stage を :decoded に
   する）が、codec=:h264 のときだけ avcC 経由の実 SPS 解析（org-iso-h264）で
   各 video track に :params（width/height/profile-idc/level-idc）を付与する
   — 画素はまだ decode しない、コンテナに埋め込まれた実メタデータの読み取り。"
  [_policy codec demuxed]
  (let [demuxed (assoc demuxed :stage :decoded)]
    (if (= codec :h264)
      (update demuxed :tracks
              (fn [tracks]
                (mapv (fn [t]
                        (if-let [params (and (= (:handler t) "vide") (h264-track-params t))]
                          (assoc t :params params)
                          t))
                      tracks)))
      demuxed)))

(defn encode
  "native encode 境界（R1: opaque passthrough）。:stage を :encoded にする。"
  [_policy _codec _opts demuxed]
  (assoc demuxed :stage :encoded))
