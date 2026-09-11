(ns utsushi.pipeline.mp4-h264-gop-test
  "Golden-vector, end-to-end test for MULTI-FRAME decode
   (`utsushi.pipeline.mp4-h264/decode-h264-frames`): a real `.mp4` FILE's
   every sample through `h264.decode/decode-gop`, compared bit-exactly
   against a real ffmpeg's OWN reconstructed pixels for the same file.

   ## The fixtures, and why there are three

   All three were produced by ffmpeg 8.1.1 / libx264 core 165 r3222
   (measured 2026-08-29) with the same encoder settings:

     -c:v libx264 -profile:v baseline -qp <q> -pix_fmt yuv420p
     -x264opts keyint=999:ref=1:bframes=0:cabac=0:8x8dct=0:me=dia:
               subme=1:partitions=none:ip-factor=1.0:scenecut=0

   `scenecut=0` is load-bearing: without it a per-frame luma change is a
   scene cut and x264 emits three I-frames, so the fixture silently stops
   being a GOP. `ip-factor=1.0` stops x264 lowering the I-frame's QP,
   which is what pushes it into Intra_4x4 (see below).

   | fixture | ffprobe pict_type | what it exercises |
   |---|---|---|
   | `mp4-h264-gop32x3.mp4` | I,P,P | I-slice + P-slices mixing P_L0_16x16 with CAVLC intra macroblocks |
   | `mp4-h264-skip32x3.mp4` | I,P,P | I-slice + P-slices that are 100% P_Skip |
   | `mp4-h264-i4x4-reject.mp4` | I,P,P | the REJECTION: an I-frame containing Intra_4x4 |

   ## The rejection is not a corner case

   libx264 cannot be told to stop using Intra_4x4. `--partitions none`
   zeroes `analyse.inter` only; x264's own printed parameter line still
   reads `analyse=0x1:0`, and `0x1` is `X264_ANALYSE_I4x4`. Measured on
   the three content variants tried for this test, x264 chose Intra_4x4
   for 8.3%-25% of I-frame macroblocks on anything with detail in it, and
   0% only on fully flat content. So `org-iso-h264`'s missing `I_NxN`
   path is the boundary between 'decodes real encoder output' and
   'doesn't', and this suite asserts BOTH sides of it rather than only
   the side that passes.

   ## What is NOT covered

   Every motion vector in the decodable fixtures is `[0 0]`, because flat
   content is the only content x264 encodes without Intra_4x4 and flat
   content has nothing to displace. `decode-gop`'s sub-pel motion
   compensation (`h264.interp`) is therefore NOT exercised by any real
   encoder output here — that gap is real and is recorded rather than
   papered over with a synthetic bitstream."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [isobmff.demux :as demux]
            [h264.bitstream :as bs]
            [utsushi.pipeline.mp4-h264 :as pipeline])
  (:import [clojure.lang ExceptionInfo]))

(defn- rd-bytes [p]
  (mapv #(bit-and (int %) 0xff)
        (with-open [in (io/input-stream (io/resource p))] (.readAllBytes in))))

(def ^:private w 32)
(def ^:private h 32)
(def ^:private luma-size (* w h))
(def ^:private chroma-size (* (quot w 2) (quot h 2)))
(def ^:private frame-size (+ luma-size chroma-size chroma-size))

(defn- ref-frame-planes
  "The i-th frame's [luma cb cr] out of a planar yuv420p reference file."
  [ref i]
  (let [b (* i frame-size)]
    [(vec (subvec ref b (+ b luma-size)))
     (vec (subvec ref (+ b luma-size) (+ b luma-size chroma-size)))
     (vec (subvec ref (+ b luma-size chroma-size) (+ b frame-size)))]))

(defn- decoded-planes [f] [(:luma f) (:cb f) (:cr f)])

(defn- assert-gop-matches-ffmpeg [fixture expected-frames]
  (let [frames (pipeline/decode-h264-frames
                (rd-bytes (str "utsushi/fixtures/" fixture ".mp4")))
        ref (rd-bytes (str "utsushi/fixtures/" fixture ".ref.yuv"))]
    (testing (str fixture ": every sample decoded, not just the first")
      (is (= expected-frames (count frames)))
      (is (= (* expected-frames frame-size) (count ref))
          "the reference YUV must hold exactly as many frames as we claim to decode"))
    (testing (str fixture ": first picture is the IDR I-slice, the rest are P-slices")
      (is (= (into [:i] (repeat (dec expected-frames) :p))
             (mapv :slice-type-class frames))))
    (doseq [i (range expected-frames)]
      (testing (str fixture " frame " i
                    ": luma+Cb+Cr bit-exact vs real ffmpeg's own decode of the same file")
        (is (= (ref-frame-planes ref i) (decoded-planes (nth frames i))))))
    frames))

(deftest gop-with-p-l0-16x16-matches-ffmpeg
  (let [frames (assert-gop-matches-ffmpeg "mp4-h264-gop32x3" 3)]
    (testing "the P-frames really did use inter prediction (P_L0_16x16), not all-intra"
      (is (some #(some #{:p-l0-16x16} (:mb-sub-types %)) (rest frames))))
    (testing "and really did also carry CAVLC intra macroblocks inside a P-slice"
      (is (some #(some false? (:mb-inter? %)) (rest frames))))
    (testing "every motion vector is zero — flat content has nothing to displace,
              so decode-gop's sub-pel motion compensation is NOT covered here"
      (is (= #{[0 0] nil} (set (mapcat :mb-mvs frames)))))))

(deftest gop-of-p-skip-matches-ffmpeg
  (let [frames (assert-gop-matches-ffmpeg "mp4-h264-skip32x3" 3)]
    (testing "the P-frames are entirely P_Skip"
      (is (every? #(every? #{:p-skip} (:mb-sub-types %)) (rest frames))))))

(deftest intra-4x4-from-a-real-encoder-is-refused-precisely
  (testing "real libx264 output containing Intra_4x4 is refused by name, not
            silently mis-decoded — libx264 cannot be configured to avoid it
            (--partitions none leaves analyse=0x1:0 = X264_ANALYSE_I4x4)"
    (let [mp4 (rd-bytes "utsushi/fixtures/mp4-h264-i4x4-reject.mp4")
          e (is (thrown? ExceptionInfo (pipeline/decode-h264-frames mp4)))]
      (is (= 0 (:mb-type (ex-data e)))
          "mb_type 0 in an I-slice is I_NxN")
      (is (= "I_NxN (Intra_4x4/8x8) not implemented" (:reason (ex-data e)))
          "the refusal names the missing syntax element, so a reader knows what
           to implement rather than that 'decoding failed'")))
  (testing "and the fixture really is a three-picture GOP — i.e. it is refused
            for its macroblock type, not for being malformed or empty"
    (let [track (pipeline/video-track
                 (demux/demux (rd-bytes "utsushi/fixtures/mp4-h264-i4x4-reject.mp4")))]
      (is (= 3 (count (:samples track)))))))

(deftest track->annexb-carries-every-sample
  (testing "the multi-frame Annex B stream carries the parameter sets once and
            then EVERY sample's NAL units; the single-sample path carries one
            sample's. The first sample is SEI+IDR (x264 writes an SEI), which is
            why the counts are not simply 2+n — asserting the kinds rather than a
            total keeps that from being papered over by an arithmetic coincidence"
    (let [track (pipeline/video-track
                 (demux/demux (rd-bytes "utsushi/fixtures/mp4-h264-gop32x3.mp4")))
          kinds (fn [bs] (mapv :kind (bs/nal-units bs)))
          whole (kinds (pipeline/track->annexb track))
          one (kinds (pipeline/sample->annexb track (first (:samples track))))]
      (is (= [:sps :pps :sei :slice-idr :slice-non-idr :slice-non-idr] whole))
      (is (= [:sps :pps :sei :slice-idr] one))
      (is (= 3 (count (filter #{:slice-idr :slice-non-idr} whole)))
          "one slice NAL per sample — this is the multi-frame capability itself")
      (is (= 1 (count (filter #{:slice-idr :slice-non-idr} one))))))
  (testing "a sub-range builds a shorter stream, so a trim can be decoded
            without re-muxing it first"
    (let [track (pipeline/video-track
                 (demux/demux (rd-bytes "utsushi/fixtures/mp4-h264-gop32x3.mp4")))
          two (pipeline/track->annexb track (subvec (:samples track) 0 2))]
      (is (= 2 (count (filter #{:slice-idr :slice-non-idr}
                              (mapv :kind (bs/nal-units two))))))
      (is (= 2 (count (pipeline/decode-track-frames track (subvec (:samples track) 0 2)))))))
  (testing "an empty sample list is refused rather than producing a
            parameter-sets-only stream the decoder would reject for a
            different reason"
    (let [track (pipeline/video-track
                 (demux/demux (rd-bytes "utsushi/fixtures/mp4-h264-gop32x3.mp4")))]
      (is (thrown-with-msg? ExceptionInfo #"no samples to decode"
                            (pipeline/track->annexb track []))))))
