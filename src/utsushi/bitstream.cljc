(ns utsushi.bitstream
  "② ビットストリーム・フレーミング層（純 cljc, ヘッダのみ）。

  設計: ADR-2606272200 §2。H.264/H.265 の NAL unit 分割、SPS/PPS の解像度・profile、
  AAC の ADTS フレーム、Opus packet 境界 — 『フレーミングとパラメータ・メタ』だけを取る。
  エントロピー復号後の画素は取らない（③ codec 層へ。kasane が JPEG を opaque に通したのと
  同じ boundary）。出力は ③ への『復号ジョブの記述』になる。")

(defn split-annexb
  "H.264/H.265 Annex B byte stream を NAL unit の [start,end) 区間列に分割する。
  start code は 0x000001 / 0x00000001。各要素 {:start :end}（payload 区間, start code 除く）。"
  [b]
  ;; TODO(R0): start-code スキャナ。emulation prevention byte(0x000003) の除去は
  ;; SPS/PPS を実際にパースする時のみ（NAL 分割では不要）。
  (throw (ex-info "TODO: split-annexb" {:len (count b)})))

(defn parse-sps
  "H.264 SPS（length-prefixed か Annex B の 1 NAL）から解像度等の最小メタを返す。
  {:profile :level :width :height :chroma}。Exp-Golomb 読みが要る。"
  [_nal]
  (throw (ex-info "TODO: parse-sps" {})))

(defn parse-adts
  "AAC ADTS フレーム列を {:sample-rate :channels :start :end} で返す（ヘッダのみ）。"
  [_b]
  (throw (ex-info "TODO: parse-adts" {})))
