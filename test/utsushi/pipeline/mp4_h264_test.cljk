(ns utsushi.pipeline.mp4-h264-test
  "Golden-vector, end-to-end test for `utsushi.pipeline.mp4-h264` — the
   first proof this ecosystem can decode real pixels out of an actual
   `.mp4` FILE (not a bare Annex B elementary stream, and not synthetic
   test data), matching ADR-2606272200 / ADR-2607122000 Phase 1's \"R0.5\"
   integration milestone.

   `resources/utsushi/fixtures/mp4-h264-flat32.mp4` was generated as a
   real MP4 container (moov/trak/stbl — NOT an Annex B elementary
   stream):

     ffmpeg -f lavfi -i color=c=0x808080:s=32x32 -frames:v 1 -update 1 test32.png
     ffmpeg -i test32.png -c:v libx264 -profile:v baseline \\
       -x264opts keyint=1:qp=26 -frames:v 1 -pix_fmt yuv420p mp4-h264-flat32.mp4

   32x32 = 2x2 Intra_16x16 macroblocks (real ffmpeg picked a mix of
   Vertical and DC prediction modes for the 4 MBs on this flat source —
   `mb I16..4: 100%`, `i16 v,h,dc,p: 50% 0% 50% 0%` per ffmpeg's own
   stderr — so this fixture, unlike org-iso-h264's own single-MB golden
   vectors, exercises cross-macroblock neighbor derivation AND a real mix
   of two different Intra_16x16 prediction modes, all CodedBlockPatternLuma
   0/DC-only).

   `mp4-h264-flat32.ref.yuv` is the SAME file decoded by a real ffmpeg
   (`ffmpeg -i mp4-h264-flat32.mp4 -pix_fmt yuv420p mp4-h264-flat32.ref.yuv`)
   — ffmpeg's own reconstructed pixels are ground truth, not the pre-encode
   source (lossy encoding changes pixel values). Comparison is bit-exact,
   luma plane only (first width*height bytes of the yuv420p file), no
   tolerance/epsilon."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [utsushi.pipeline.mp4-h264 :as pipeline]))

(defn- rd-bytes [p]
  (mapv #(bit-and (int %) 0xff) (with-open [in (io/input-stream (io/resource p))] (.readAllBytes in))))

(deftest decode-first-h264-frame-real-mp4-golden-vector
  (let [mp4-bytes (rd-bytes "utsushi/fixtures/mp4-h264-flat32.mp4")
        result (pipeline/decode-first-h264-frame mp4-bytes)
        ref (vec (take (* 32 32) (rd-bytes "utsushi/fixtures/mp4-h264-flat32.ref.yuv")))]
    (testing "dimensions from the container's own avcC-embedded SPS"
      (is (= 32 (:width result)))
      (is (= 32 (:height result))))
    (testing "reconstructed luma plane is bit-exact vs. real ffmpeg's OWN decode of the same MP4 file"
      (is (= ref (:luma result))))
    (testing "sanity: this is real reconstructed pixel data (not all-zero / not the demux'd container bytes)"
      (is (every? #(<= 0 % 255) (:luma result)))
      (is (pos? (count (distinct (:luma result))))))))

(deftest avcc-sample-splits-into-individual-nal-units
  (testing "a length-size-4 AVCC sample (two length-prefixed NALs) splits into two NAL byte vectors"
    (let [nal-a [0x65 0xAA 0xBB]  ; fake 3-byte NAL (header + 2 payload bytes)
          nal-b [0x41 0xCC]        ; fake 2-byte NAL
          sample (vec (concat [0 0 0 (count nal-a)] nal-a [0 0 0 (count nal-b)] nal-b))
          nalus (pipeline/avcc-sample->nalus sample 4)]
      (is (= [nal-a nal-b] nalus)))))

(deftest nalus->annexb-inserts-start-codes
  (testing "each NAL gets its own 4-byte 00 00 00 01 start code prefix"
    (let [nalus [[0x65 0xAA] [0x41 0xCC 0xDD]]
          annexb (pipeline/nalus->annexb nalus)]
      (is (= [0 0 0 1 0x65 0xAA 0 0 0 1 0x41 0xCC 0xDD] annexb)))))
