(ns utsushi.pipeline.remux
  "Stream-copy (re-encode-free) trim and concat over a demuxed MP4's own
   sample list, muxed back to MP4 — `ffmpeg -ss/-t -c copy` and
   `ffmpeg -f concat -c copy`, in pure cljc.

   The container transforms themselves live in `org-iso-isobmff`
   (`isobmff.remux/trim`, `concat-streams`, `remux`) — that is where the
   ISO/IEC 14496-12 knowledge belongs, and re-deriving it here would be a
   second opinion about the same spec. What this namespace adds is the
   orchestration `utsushi` owns: MP4 bytes in, MP4 bytes out, and a
   sample-INDEX facing trim, because the thing a caller actually asks for
   is `-frames:v 2` and translating that to a pts window is arithmetic
   nobody should repeat at each call site.

   ## Verification is by decoded pixels, never by container bytes

   Two muxers that write the same pictures legitimately disagree about box
   order, `tkhd` width/height, `free` padding and chunk offsets. Comparing
   this namespace's output to `ffmpeg -c copy`'s output byte-for-byte would
   fail for reasons that have nothing to do with correctness. The test
   (`test/utsushi/pipeline/remux_test.clj`) therefore decodes both and
   compares PIXELS, against reference YUV that a real ffmpeg produced from
   its OWN stream-copy edit of the same source.

   ## What a stream-copy trim cannot do

   Dropping the IDR at the head of a GOP leaves P-slices with no reference
   frame. Nothing here rounds a trim to the nearest sync sample or reports
   that in advance: `h264.decode/decode-gop` already answers it exactly (a
   P-slice with no reference frame throws), and a weaker second opinion
   here would be the more likely one to be wrong. `sync-sample-indices`
   exists so a caller can choose a decodable window rather than discover an
   undecodable one."
  (:require [isobmff.demux :as demux]
            [isobmff.remux :as remux]))

(defn sync-sample-indices
  "Indices (0-based, into `:samples`) of a track's sync samples — the
   points a stream-copy trim may begin at and still decode."
  [track]
  (vec (keep-indexed (fn [i s] (when (:keyframe s) i)) (:samples track))))

(defn trim-samples
  "Restrict every track of a demuxed structure to sample indices
   `[start end)` — pure selection, no codec bytes touched.

   Sample-index rather than pts, because that is the unit the caller has
   (`-frames:v N`) and the unit `decode-gop` consumes. `isobmff.remux/trim`
   remains available for a pts window; this is not a replacement for it."
  [demuxed start end]
  (when (or (neg? start) (< end start))
    (throw (ex-info "utsushi.pipeline.remux: trim range is empty or inverted"
                    {:start start :end end})))
  (update demuxed :tracks
          (fn [tracks]
            (mapv (fn [t]
                    (let [ss (:samples t)]
                      (assoc t :samples (vec (subvec ss (min start (count ss))
                                                     (min end (count ss)))))))
                  tracks))))

(defn trim
  "MP4 bytes → MP4 bytes, keeping only sample indices `[start end)` of
   every track. The stream-copy counterpart of `ffmpeg -ss/-t -c copy`."
  [mp4-bytes start end]
  (-> (demux/demux (vec mp4-bytes))
      (trim-samples start end)
      (remux/remux)))

(defn concat-demuxed
  "Concatenate demuxed structures track-by-track, left to right. Two
   inputs is `isobmff.remux/concat-streams`; this folds any number of them
   so a caller does not have to know that concat is associative here."
  [demuxeds]
  (when (empty? demuxeds)
    (throw (ex-info "utsushi.pipeline.remux: nothing to concatenate (empty input list)" {})))
  (reduce remux/concat-streams demuxeds))

(defn concat-mp4s
  "MP4 byte vectors → one MP4 byte vector, samples appended in order with
   the later streams' presentation timestamps shifted past the earlier
   ones. The stream-copy counterpart of `ffmpeg -f concat -c copy`.

   R0 assumes a compatible `stsd` across inputs (the first stream's is the
   one written), which is exactly what `ffmpeg -f concat -c copy` assumes
   too — it is not a weakening peculiar to this implementation."
  [mp4-byte-vectors]
  (-> (mapv #(demux/demux (vec %)) mp4-byte-vectors)
      (concat-demuxed)
      (remux/remux)))
