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

   Only the FIRST sample of the FIRST H.264 (`vide`-handler) track is
   decoded (`decode-frame`/`decode-first-h264-frame` default to
   `sample-idx` 0) — matching `h264.decode/decode-idr-frame`'s own single-
   IDR-I-slice-per-call scope (see its docstring). Multi-frame streams,
   P/B slices, and remux/trim are out of scope here (tracked as follow-up
   in this repo's ADR)."
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
