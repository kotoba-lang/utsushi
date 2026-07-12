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
            [isobmff.bytes :as by]
            [h264.rbsp :as rbsp]
            [h264.sps :as sps]
            [h264.pps :as pps]))

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

;; ---- H.264 encode: :params (width/height/profile-idc/level-idc) → avcC-embedded :stsd ----
;; Wave 4 wiring (ADR-2607121400): the encode-side mirror of h264-track-params
;; above. org-iso-h264's h264.sps/encode + h264.pps/encode only produce
;; parameter-set NALUs (no macroblock/pixel/CAVLC/CABAC — see org-iso-h264's
;; own docstrings), so this wires exactly that scope: given a video track's
;; :params (the same shape decode above produces), synthesize a real avcC
;; (AVCDecoderConfigurationRecord, ISO/IEC 14496-15) box embedding an encoded
;; SPS+PPS, wrapped in a minimal avc1 VisualSampleEntry + stsd box — the same
;; byte layout find-avcc/h264-track-params reads. This does NOT produce
;; encoded frame data (no :samples bytes are written) — only the parameter-set
;; portion of the container metadata. Round-trip verified against decode in
;; codec_test.cljc.

(defn- h264-avcc-payload
  "AVCDecoderConfigurationRecord payload embedding one SPS + one PPS NALU
   built from `params` ({:profile-idc :level-idc :width :height}). Byte
   layout matches what h264-track-params/find-avcc read (configVersion,
   profile_idc, profile_compatibility, level_idc, lengthSizeMinusOne,
   reserved+numSPS, sps-len16, sps-bytes, numPPS, pps-len16, pps-bytes)."
  [{:keys [profile-idc level-idc] :as params}]
  (let [sps (rbsp/escape (sps/encode params))
        pp  (rbsp/escape (pps/encode {}))]
    (vec (concat [1 profile-idc 0 level-idc 0xff]      ; configVersion/profile_idc/profile_compat/level_idc/lengthSizeMinusOne(=4)
                 [(bit-or 0xE0 1)]                       ; reserved(111)+numOfSequenceParameterSets(1)
                 (by/wu16 (count sps)) sps
                 [1]                                     ; numOfPictureParameterSets
                 (by/wu16 (count pp)) pp))))

(defn- h264-avcc-stsd
  "Minimal `stsd` box bytes (FullBox header + entry-count=1 + one `avc1`
   VisualSampleEntry wrapping the avcC box from `h264-avcc-payload`) — the
   exact shape find-avcc/h264-track-params parse back out. The 78
   VisualSampleEntry fixed fields (after avc1's own 8-byte box header,
   totalling the 86-byte visual-sample-entry-fixed-header find-avcc skips)
   are zero-filled: decode never reads them (width/height come from the SPS,
   not the box's own width/height fields), only their byte count matters."
  [params]
  (let [avcc-payload (h264-avcc-payload params)
        avcc-box     (vec (concat (by/wu32 (+ 8 (count avcc-payload))) (by/wstr "avcC") avcc-payload))
        avc1-fixed   (vec (repeat 78 0))
        avc1-box     (vec (concat (by/wu32 (+ 86 (count avcc-box))) (by/wstr "avc1")
                                   avc1-fixed avcc-box))
        stsd-body    (vec (concat [0 0 0 0] (by/wu32 1) avc1-box))] ; FullBox version/flags(4) + entry-count(4)
    (vec (concat (by/wu32 (+ 8 (count stsd-body))) (by/wstr "stsd") stsd-body))))

(defn encode
  "native encode 境界。R1: bytes は opaque passthrough（:stage を :encoded に
   する）が、codec=:h264 のときだけ、各 video track の :params
   （width/height/profile-idc/level-idc — decode が生成するのと同じ形）から
   avcC 埋め込み :stsd を実合成する（org-iso-h264 の SPS/PPS encode +
   emulation-prevention escape）。パラメータセット層のみ — 画素/フレームは
   まだ encode しない（org-iso-h264 自身のスコープ限定と同じ）。"
  [_policy codec _opts demuxed]
  (let [demuxed (assoc demuxed :stage :encoded)]
    (if (= codec :h264)
      (update demuxed :tracks
              (fn [tracks]
                (mapv (fn [t]
                        (if (and (= (:handler t) "vide") (:params t))
                          (assoc t :stsd (h264-avcc-stsd (:params t)))
                          t))
                      tracks)))
      demuxed)))
