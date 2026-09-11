(ns utsushi.pipeline.h264-mp4
  "Raw pixels (YUV420p) → real H.264-encoded → real MP4 FILE bytes,
   end-to-end. This is the ENCODE-direction mirror of
   `utsushi.pipeline.mp4-h264` (which goes MP4 file → decoded pixels, ADR-
   2606272200 / ADR-2607122000 Phase 1 \"R0.5\"): given raw luma (+
   optional chroma) planes, produce an actual `.mp4` file's bytes that a
   real, independent `ffmpeg` can decode — \"utsushi can produce what
   ffmpeg can play\", the encode-side half of that same integration claim.

   ## Why this wiring didn't already exist

   `h264.encode/encode-idr-luma-frame` (org-iso-h264) already produces a
   real, ffmpeg-validated H.264 Annex B elementary stream (SPS + PPS + one
   IDR I-slice NAL) from raw pixel planes. `isobmff.mux/mux`
   (org-iso-isobmff) already lays out `ftyp+mdat+moov` from a `{:tracks
   [...] :timescale}` structure. But neither of those, nor
   `utsushi.codec`'s existing `h264-avcc-stsd` (which only synthesizes a
   parameter-set-only avcC from an independently-supplied `:params` map,
   for `utsushi.codec/encode`'s R1 opaque-passthrough contract), assembles
   a *sample-bearing* MP4: `isobmff.mux/minimal-stsd` is explicitly an
   opaque placeholder with no codec config, and a real remux normally
   copies a SOURCE file's stsd verbatim — there is no source here, since
   this is a fresh encode. This namespace is exactly that missing link:
   AVCC sample framing + a real avc1/avcC `stsd` (ISO/IEC 14496-15
   AVCDecoderConfigurationRecord), built directly from the SPS/PPS NAL
   units `h264.encode` itself just produced (NOT re-derived independently
   via `h264.sps/encode` with separately-guessed parameters — the PPS in
   particular encodes `pic_init_qp`, and the slice header's
   `slice_qp_delta` is computed relative to whatever PPS
   `encode-idr-luma-frame` actually used internally; re-deriving a PPS
   with different parameters would silently declare the wrong QP to a
   real decoder even though the bitstream itself is correct)."
  (:require [h264.encode :as h264-encode]
            [h264.bitstream :as bs]
            [isobmff.bytes :as by]
            [isobmff.mux :as mux]))

;; ---- AVCC (length-prefixed) sample framing — the inverse of
;;      utsushi.pipeline.mp4-h264/avcc-sample->nalus / nalus->annexb ----

(defn- write-be-uint
  "Write `n` as an `nbytes`-byte big-endian unsigned integer (vector of
   0..255 ints)."
  [n nbytes]
  (vec (for [i (range nbytes)]
         (bit-and (bit-shift-right n (* 8 (- nbytes i 1))) 0xff))))

(defn nalus->avcc-sample
  "Concatenate a seq of NAL unit byte vectors (each including its 1-byte
   NAL header — the shape `h264.bitstream/nal-units` attaches under
   `:bytes`) into one AVCC-framed sample byte vector (each NAL prefixed by
   a `length-size`-byte big-endian length, NO start codes) — the exact
   inverse of `utsushi.pipeline.mp4-h264/avcc-sample->nalus`."
  [nalus length-size]
  (vec (mapcat (fn [nalu] (concat (write-be-uint (count nalu) length-size) nalu))
               nalus)))

(defn annexb->param-and-slice-nalus
  "Split an Annex B stream (as `h264.encode/encode-idr-luma-frame` returns
   under `:bytes`) into `{:sps-nalu :pps-nalu :slice-nalus}` — the SPS NAL,
   the PPS NAL (each including their 1-byte NAL header, ready for
   `avcc-payload`), and the ordered seq of IDR-slice NAL byte vectors
   (normally exactly one, since `encode-idr-luma-frame` emits a single
   whole-picture I-slice — kept as a seq rather than assumed singular so a
   future multi-slice encoder need not change this fn's shape)."
  [annexb-bytes]
  (let [units (bs/nal-units annexb-bytes)
        sps-u (first (filter #(= :sps (:kind %)) units))
        pps-u (first (filter #(= :pps (:kind %)) units))
        slice-us (filter #(= :slice-idr (:kind %)) units)]
    (when-not sps-u
      (throw (ex-info "utsushi.pipeline.h264-mp4: no SPS NAL in encoded Annex B stream" {})))
    (when-not pps-u
      (throw (ex-info "utsushi.pipeline.h264-mp4: no PPS NAL in encoded Annex B stream" {})))
    (when (empty? slice-us)
      (throw (ex-info "utsushi.pipeline.h264-mp4: no IDR slice NAL in encoded Annex B stream" {})))
    {:sps-nalu (:bytes sps-u)
     :pps-nalu (:bytes pps-u)
     :slice-nalus (mapv :bytes slice-us)}))

;; ---- avcC (AVCDecoderConfigurationRecord, ISO/IEC 14496-15 §5.2.4.1) —
;;      the inverse of utsushi.codec/avcc-config ----

(def ^:private avcc-length-size
  "lengthSizeMinusOne+1 this pipeline always writes (4 — the common/real-
   encoder default; matches the same constant `utsushi.codec/h264-avcc-payload`
   uses for its own, independently-synthesized avcC)."
  4)

(defn avcc-payload
  "Build an AVCDecoderConfigurationRecord payload (ISO/IEC 14496-15) from
   the REAL SPS/PPS NAL units `annexb->param-and-slice-nalus` extracted
   (each including their 1-byte NAL header) — the byte-for-byte inverse of
   `utsushi.codec/avcc-config`'s read side: configurationVersion=1,
   AVCProfileIndication/profile_compatibility/AVCLevelIndication read
   directly off the SPS NALU's own bytes (indices 1/2/3 — the SPS RBSP
   layout `h264.sps/encode`/`parse` both use, NAL header at index 0),
   lengthSizeMinusOne=3 (4-byte length prefixes), exactly one SPS + one
   PPS NALU (matching `h264.encode/encode-idr-luma-frame`'s own single-
   SPS/single-PPS-per-frame scope)."
  [sps-nalu pps-nalu]
  (vec (concat [1 (nth sps-nalu 1) (nth sps-nalu 2) (nth sps-nalu 3)
                (bit-or 0xFC (dec avcc-length-size))]         ; reserved(111111)+lengthSizeMinusOne
               [(bit-or 0xE0 1)]                                ; reserved(111)+numOfSequenceParameterSets(1)
               (by/wu16 (count sps-nalu)) sps-nalu
               [1]                                               ; numOfPictureParameterSets
               (by/wu16 (count pps-nalu)) pps-nalu)))

;; ---- generic box-writing helpers (local — org-iso-isobmff's own `box`/
;;      `fbox` in isobmff.mux are private write helpers, not part of that
;;      repo's public API, and org-iso-isobmff is read-only for this task;
;;      isobmff.box itself is a READ-side box-tree parser only. These
;;      mirror isobmff.mux's private helpers exactly, using only public
;;      isobmff.bytes primitives.) ----

(defn- box [type payload]
  (let [p (vec payload) size (+ 8 (count p))]
    (into (into (by/wu32 size) (by/wstr type)) p)))

(defn- fbox [type version flags payload]
  (box type (concat (by/wu8 version) (by/wu24 flags) payload)))

;; VisualSampleEntry fixed-field layout after SampleEntry's own base
;; (ISO/IEC 14496-12 §8.5.2 / §12.1.3): pre_defined(2) reserved(2)
;; pre_defined[3](12) width(2) height(2) horizresolution(4)
;; vertresolution(4) reserved(4) frame_count(2) compressorname(32)
;; depth(2) pre_defined=-1(2) — 70 bytes, which combined with SampleEntry's
;; own 8-byte base (reserved(6)+data_reference_index(2)) is exactly the
;; 78-byte fixed header `utsushi.codec/visual-sample-entry-fixed-header`
;; (86 = 8 box header + 78) already assumes when READING an avc1 entry —
;; this is the WRITE side producing that same shape, but with REAL
;; width/height/etc. (not zero-filled) since a real `ffmpeg` — unlike
;; utsushi's own decode path, which only reads avcC's embedded SPS — reads
;; these container-level fields too.

(defn avc1-sample-entry
  "Build a full `avc1` VisualSampleEntry box (SampleEntry base +
   VisualSampleEntry fixed fields, with REAL `width`/`height`, +
   `avcc-box-bytes` as its one child box) — real-world-shaped, not the
   zero-filled placeholder `utsushi.codec/h264-avcc-stsd` writes for its
   own opaque-passthrough R1 contract."
  [width height avcc-box-bytes]
  (box "avc1"
       (concat (repeat 6 0) (by/wu16 1)                        ; SampleEntry: reserved(6) + data_reference_index=1
               (by/wu16 0) (by/wu16 0)                          ; pre_defined, reserved
               (mapcat by/wu32 [0 0 0])                         ; pre_defined[3]
               (by/wu16 width) (by/wu16 height)
               (by/wu32 0x00480000) (by/wu32 0x00480000)        ; horizresolution/vertresolution = 72dpi
               (by/wu32 0)                                       ; reserved
               (by/wu16 1)                                       ; frame_count
               (repeat 32 0)                                    ; compressorname (empty Pascal string)
               (by/wu16 0x0018)                                 ; depth = 0x0018
               (by/wu16 0xFFFF)                                 ; pre_defined = -1
               avcc-box-bytes)))

(defn h264-stsd
  "Build a real `stsd` box (FullBox header + entry-count=1 + one `avc1`
   VisualSampleEntry wrapping a real `avcC` box) from `width`/`height` and
   the real SPS/PPS NAL units of an encoded frame — a valid ISO/IEC
   14496-15 H.264 sample description a real `ffmpeg`/any spec-compliant
   demuxer can read, unlike `isobmff.mux/minimal-stsd` (opaque R0
   placeholder) or `utsushi.codec/h264-avcc-stsd` (parameter-set-only,
   zero-filled visual fields, meant only for this ecosystem's own R1
   opaque decode/encode round trip)."
  [width height sps-nalu pps-nalu]
  (let [avcc-box (box "avcC" (avcc-payload sps-nalu pps-nalu))
        avc1-box (avc1-sample-entry width height avcc-box)]
    (fbox "stsd" 0 0 (concat (by/wu32 1) avc1-box))))

;; ---- top-level pipeline ----

(defn encode-frame->track
  "Encode `{:width :height :qp :luma :cb :cr}` (same shape
   `h264.encode/encode-idr-luma-frame` takes) to one `isobmff.mux/mux`-
   ready video track map: `{:track-id :handler \"vide\" :timescale :stsd
   :samples [{:bytes :size :duration :keyframe true}]}` — a real avcC/avc1
   `stsd` (built from the SAME SPS/PPS NAL units actually used to encode
   the slice, see `h264-stsd`) and one AVCC-framed sample holding the
   encoded IDR slice."
  [{:keys [width height timescale duration track-id]
    :or {timescale 30 duration 1 track-id 1}
    :as frame-params}]
  (let [{:keys [bytes]} (h264-encode/encode-idr-luma-frame
                          (select-keys frame-params [:width :height :qp :luma :cb :cr]))
        {:keys [sps-nalu pps-nalu slice-nalus]} (annexb->param-and-slice-nalus bytes)
        stsd (h264-stsd width height sps-nalu pps-nalu)
        sample-bytes (nalus->avcc-sample slice-nalus avcc-length-size)]
    {:track-id track-id
     :handler "vide"
     :timescale timescale
     :stsd stsd
     :samples [{:bytes sample-bytes
                :size (count sample-bytes)
                :duration duration
                :keyframe true}]}))

(defn encode-frame->mp4
  "End-to-end entry point, the encode-direction mirror of
   `utsushi.pipeline.mp4-h264/decode-first-h264-frame`: raw pixel planes
   `{:width :height :qp :luma :cb :cr}` → real MP4 FILE bytes (`ftyp+mdat+
   moov`, via `isobmff.mux/mux`) holding one real H.264-encoded IDR frame
   in a real avc1/avcC `stsd` — the first concrete proof this ecosystem
   can produce an actual `.mp4` file a real, independent `ffmpeg` can
   play, the way `ffmpeg`/`x264` would encode-and-mux it (mirrors ADR-
   2606272200 §3 / ADR-2607122000 Phase 1 \"R0.5\", encode direction)."
  [frame-params]
  (let [track (encode-frame->track frame-params)]
    (mux/mux {:tracks [track] :timescale (:timescale track)})))
