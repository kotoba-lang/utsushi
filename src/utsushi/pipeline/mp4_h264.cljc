(ns utsushi.pipeline.mp4-h264
  "MP4 (ISOBMFF container, AVCC-framed H.264) → real decoded pixels,
   end-to-end. This is the integration proof ADR-2606272200 (`utsushi`
   itself) and ADR-2607122000 Phase 1 (\"R0.5\") ask for: given an actual
   `.mp4` FILE's bytes (not a bare H.264 elementary stream, and not
   synthetic test data), demux the container, extract the H.264 parameter
   sets (avcC), split the AVCC-framed sample into NAL units, and hand a
   real Annex B stream to `org-iso-h264`'s `h264.decode/decode-idr-frame`
   to get back actual luma pixels — \"utsushi can do what ffmpeg does\"
   for a first, narrow, real slice.

   ## Why this wiring didn't already exist

   `isobmff.demux` (org-iso-isobmff) returns each sample's bytes AVCC-
   framed (ISO/IEC 14496-15 §5.2.4.1 — each NAL unit prefixed by a
   `lengthSizeMinusOne+1`-byte big-endian length, no start codes at all).
   `h264.decode/decode-idr-frame` (org-iso-h264) — and the
   `h264.bitstream/nal-units` it's built on — only understands Annex B
   framing (`0x000001`/`0x00000001` start codes). `utsushi.codec`'s
   existing `h264-track-params` already reaches into avcC to read the SPS
   for width/height metadata, but stops there — it never assembles a
   decodable Annex B stream or calls `h264.decode`. This namespace is
   exactly that missing link: AVCC sample framing → Annex B → pixels.

   ## Scope

   Two entry points, both real:

   - `decode-first-h264-frame` — ONE sample through
     `h264.decode/decode-idr-frame` (single IDR I-slice per call).
   - `decode-h264-frames` — EVERY sample of the track through
     `h264.decode/decode-gop`, as one Annex B stream (SPS + PPS from
     avcC, then every sample's NAL units in decode order). Returns the
     whole decoded frame sequence: IDR followed by P-frames, with
     `org-iso-h264`'s real single-reference inter prediction.

   ## What a real encoder emits that this cannot yet decode

   `decode-gop`'s macroblock scope is Intra_16x16 (I slices), and
   P_Skip / P_L0_16x16 / P_16x8 / P_8x16 / P_8x8 plus CAVLC intra
   macroblocks (P slices). It has no Intra_4x4 (`I_NxN`) path.

   That is not a corner case: **libx264 cannot be told to stop using
   Intra_4x4.** `--partitions none` zeroes only `analyse.inter`; the
   printed parameter line still reads `analyse=0x1:0`, where `0x1` is
   `X264_ANALYSE_I4x4` (measured 2026-08-29, x264 core 165 r3222 via
   ffmpeg 8.1.1). Any content with detail therefore contains I_NxN
   macroblocks and is rejected with

     h264.decode: only Intra_16x16 mb_type (1..24) is supported
     {:mb-type 0 :reason `I_NxN (Intra_4x4/8x8) not implemented`}

   Only fully flat content encodes as 100% Intra_16x16. The fixtures in
   `resources/utsushi/fixtures/` cover BOTH sides of that line — see
   `test/utsushi/pipeline/mp4_h264_gop_test.clj`, which asserts the
   decode for the ones inside it and the exact rejection for the one
   outside."
  (:require [isobmff.demux :as demux]
            [utsushi.codec :as codec]
            [h264.decode :as h264-decode]))

(def ^:private annexb-start-code
  "4-byte Annex B long start code (00 00 00 01) — used unconditionally
   here (both the 3- and 4-byte forms are spec-legal per
   `h264.bitstream/split-annexb`, which accepts either)."
  [0 0 0 1])

(defn- read-be-uint
  "Read an `n`-byte big-endian unsigned integer from `buf` at `off`."
  [buf off n]
  (reduce (fn [acc i] (bit-or (bit-shift-left acc 8) (nth buf (+ off i))))
          0 (range n)))

(defn avcc-sample->nalus
  "Split one AVCC-framed sample byte vector (as `isobmff.demux` returns
   under a sample's `:bytes` — length-prefixed NAL units, NOT Annex B
   start-code delimited) into individual NAL unit byte vectors (each
   including its 1-byte NAL header), using `length-size` (1..4, from
   `utsushi.codec/avcc-config`'s `:length-size`, i.e. avcC's
   `lengthSizeMinusOne + 1`) as the per-NAL length-prefix width."
  [sample-bytes length-size]
  (let [buf (vec sample-bytes)
        n (count buf)]
    (loop [off 0 acc []]
      (if (>= off n)
        acc
        (let [len (read-be-uint buf off length-size)
              start (+ off length-size)
              end (+ start len)]
          (when (> end n)
            (throw (ex-info "utsushi.pipeline.mp4-h264: AVCC NAL length runs past sample end (corrupt sample bytes or wrong length-size)"
                             {:offset off :nal-length len :sample-size n :length-size length-size})))
          (recur end (conj acc (subvec buf start end))))))))

(defn nalus->annexb
  "Concatenate a seq of NAL unit byte vectors (each including its 1-byte
   NAL header — the shape `avcc-sample->nalus`/`utsushi.codec/avcc-config`
   produce) into one Annex-B start-code-delimited byte stream, the framing
   `h264.bitstream/nal-units` (and therefore `h264.decode/decode-idr-frame`)
   requires."
  [nalus]
  (vec (mapcat #(concat annexb-start-code %) nalus)))

(defn video-track
  "First `vide`-handler track in a `isobmff.demux/demux`'d MP4 map, or nil."
  [demuxed]
  (first (filter #(= (:handler %) "vide") (:tracks demuxed))))

(defn sample->annexb
  "Build the Annex B byte stream `h264.decode/decode-idr-frame` needs
   (SPS NALU + PPS NALU, from `track`'s avcC, followed by `sample`'s own
   NAL units, all start-code delimited) for one AVCC-framed `sample` (an
   element of `track`'s `:samples`, as `isobmff.demux` returns) of a
   demuxed H.264 video `track`.

   `decode-idr-frame` finds its SPS/PPS/IDR-slice NALs by scanning ONE
   Annex B stream for their `:kind` (see org-iso-h264's `h264.decode`) —
   MP4's avcC carries the parameter sets separately from sample data, so
   they must be prepended here rather than assumed already present in the
   sample bytes (real encoders/muxers commonly omit repeating SPS/PPS
   in-band per sample once they're in avcC)."
  [track sample]
  (let [avcc (codec/find-avcc (:stsd track))
        _ (when-not avcc
            (throw (ex-info "utsushi.pipeline.mp4-h264: no avcC box in stsd (not an H.264/avc1 track?)"
                             {:track-id (:track-id track)})))
        {:keys [length-size sps-nalus pps-nalus]} (codec/avcc-config avcc)
        _ (when (empty? sps-nalus)
            (throw (ex-info "utsushi.pipeline.mp4-h264: avcC has no SPS NALU" {:track-id (:track-id track)})))
        _ (when (empty? pps-nalus)
            (throw (ex-info "utsushi.pipeline.mp4-h264: avcC has no PPS NALU" {:track-id (:track-id track)})))
        sample-nalus (avcc-sample->nalus (:bytes sample) length-size)]
    (nalus->annexb (concat sps-nalus pps-nalus sample-nalus))))

(defn decode-frame
  "Decode `sample-idx`'s (default 0 — the first sample, which should be an
   IDR frame) pixels of an H.264 `track` (a demuxed video track map, from
   `video-track`/`isobmff.demux/demux`). Returns {:width :height :luma},
   the same shape `h264.decode/decode-idr-frame` returns — real
   reconstructed pixels, not an opaque passthrough."
  ([track] (decode-frame track 0))
  ([track sample-idx]
   (let [sample (nth (:samples track) sample-idx)]
     (h264-decode/decode-idr-frame (sample->annexb track sample)))))

(defn decode-first-h264-frame
  "End-to-end entry point: raw MP4 FILE bytes → {:width :height :luma}
   for the first sample of the first H.264 (`vide`) track. Wires
   `isobmff.demux` (container demux) + `utsushi.codec/avcc-config`
   (avcC → SPS/PPS/length-size) + this namespace's AVCC→Annex-B framing
   conversion to `h264.decode/decode-idr-frame` (org-iso-h264's real
   pixel decoder) — the first concrete proof that this ecosystem can take
   an actual `.mp4` file and produce actual pixels, the way `ffmpeg`
   would (ADR-2606272200 §3 / ADR-2607122000 Phase 1 \"R0.5\")."
  [mp4-bytes]
  (let [demuxed (demux/demux (vec mp4-bytes))
        track (video-track demuxed)]
    (when-not track
      (throw (ex-info "utsushi.pipeline.mp4-h264: no video (vide) track found in MP4" {})))
    (decode-frame track 0)))

;; ── multi-frame (whole-GOP) decode ───────────────────────────────────────

(defn track->annexb
  "Build ONE Annex B elementary stream out of a whole demuxed H.264
   `track`: the avcC parameter sets (every SPS NALU, then every PPS NALU)
   followed by the NAL units of every sample in `samples` (default: the
   track's own `:samples`), in decode order, each start-code delimited.

   This is the framing `h264.decode/decode-gop` consumes. It differs from
   `sample->annexb` only in taking every sample rather than one — but that
   difference is the whole multi-frame capability, because `decode-gop`
   finds its pictures by scanning ONE stream for slice NALs and carries
   each decoded picture forward as the next P-slice's reference frame.

   `samples` lets a caller decode a sub-range (a trim), which is only
   decodable when it starts at an IDR — nothing here checks that, because
   `decode-gop` reports it precisely (a P-slice with no reference frame
   throws) and re-deriving the answer here would be a second, weaker
   opinion about the same bitstream."
  ([track] (track->annexb track (:samples track)))
  ([track samples]
   (let [avcc (codec/find-avcc (:stsd track))
         _ (when-not avcc
             (throw (ex-info "utsushi.pipeline.mp4-h264: no avcC box in stsd (not an H.264/avc1 track?)"
                              {:track-id (:track-id track)})))
         {:keys [length-size sps-nalus pps-nalus]} (codec/avcc-config avcc)
         _ (when (empty? sps-nalus)
             (throw (ex-info "utsushi.pipeline.mp4-h264: avcC has no SPS NALU" {:track-id (:track-id track)})))
         _ (when (empty? pps-nalus)
             (throw (ex-info "utsushi.pipeline.mp4-h264: avcC has no PPS NALU" {:track-id (:track-id track)})))
         _ (when (empty? samples)
             (throw (ex-info "utsushi.pipeline.mp4-h264: no samples to decode (empty sample list)"
                              {:track-id (:track-id track)})))
         sample-nalus (mapcat #(avcc-sample->nalus (:bytes %) length-size) samples)]
     (nalus->annexb (concat sps-nalus pps-nalus sample-nalus)))))

(defn decode-track-frames
  "Decode EVERY sample of a demuxed H.264 `track` (or just `samples`) into
   a vector of frame maps, in decode order — each the shape
   `h264.decode/decode-idr-frame` returns ({:width :height :luma :cb :cr}
   plus per-macroblock observation keys). Real reconstructed pixels for a
   whole GOP, not just its first picture."
  ([track] (h264-decode/decode-gop (track->annexb track)))
  ([track samples] (h264-decode/decode-gop (track->annexb track samples))))

(defn decode-h264-frames
  "End-to-end multi-frame entry point: raw MP4 FILE bytes → a vector of
   decoded frames for the first H.264 (`vide`) track.

   The multi-frame counterpart of `decode-first-h264-frame`: demux the
   container, take the avcC parameter sets and EVERY sample's NAL units
   into one Annex B stream, and hand it to `h264.decode/decode-gop`, which
   decodes the IDR and then each P-frame against the immediately preceding
   decoded picture."
  [mp4-bytes]
  (let [demuxed (demux/demux (vec mp4-bytes))
        track (video-track demuxed)]
    (when-not track
      (throw (ex-info "utsushi.pipeline.mp4-h264: no video (vide) track found in MP4" {})))
    (decode-track-frames track)))
