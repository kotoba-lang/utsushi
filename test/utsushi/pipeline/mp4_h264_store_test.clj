(ns utsushi.pipeline.mp4-h264-store-test
  "Integration test wiring `utsushi.pipeline.mp4-h264` (real MP4 → Annex B
   bytes) directly into `utsushi.codec.store/decode-frame!` (Annex B bytes →
   persisted decode-state job), the two independently-landed R0.5 features
   (ADR-2606272200 / ADR-2607122000 Phase 1) this repo's CLAUDE.md flags as
   \"already composable, but never actually connected in a test\".

   Neither `utsushi.pipeline.mp4-h264-test` (which calls
   `decode-first-h264-frame` and checks its in-memory return value only) nor
   `utsushi.codec-store-test` (which feeds `decode-frame!` a bare H.264
   elementary-stream fixture, `flat16-dc-only.h264`, not an MP4) exercises
   the actual hand-off: `pipeline/sample->annexb`'s Annex B byte vector, fed
   unmodified into `store/decode-frame!`, going through a REAL MP4 demux
   (`mp4-h264-flat32.mp4`) all the way to a persisted, bit-exact-vs-ffmpeg
   `:decode/frame-blob`.

   This test's job is specifically that hand-off and the Store's state
   transitions around it — not to re-validate `h264.decode/decode-idr-frame`
   pixel correctness (org-iso-h264's own suite) or the AVCC→Annex-B framing
   logic in isolation (`utsushi.pipeline.mp4-h264-test`'s
   `avcc-sample-splits-into-individual-nal-units` /
   `nalus->annexb-inserts-start-codes`), and it deliberately does NOT
   re-implement `utsushi.codec-store-test`'s already-covered
   corrupt-bytes/non-h264-codec `:decode/status :failed` coverage — see
   that namespace for the failure-path tests."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [isobmff.demux :as demux]
            [langchain.db :as db]
            [utsushi.pipeline.mp4-h264 :as pipeline]
            [utsushi.codec.store :as store]))

(defn- rd-bytes [p]
  (mapv #(bit-and (int %) 0xff) (with-open [in (io/input-stream (io/resource p))] (.readAllBytes in))))

(defn- fresh-conn [] (db/create-conn store/decode-schema))

(deftest mp4-pipeline-annexb-flows-through-decode-store-golden-vector
  (let [mp4-bytes (rd-bytes "utsushi/fixtures/mp4-h264-flat32.mp4")
        demuxed   (demux/demux (vec mp4-bytes))
        track     (pipeline/video-track demuxed)
        sample    (first (:samples track))
        annexb    (pipeline/sample->annexb track sample)
        conn      (fresh-conn)
        ref       (vec (take (* 32 32) (rd-bytes "utsushi/fixtures/mp4-h264-flat32.ref.yuv")))]
    (testing "job absent before decode"
      (is (nil? (store/job-entity conn "mp4-job-1" {}))))
    (let [job (store/decode-frame! conn "mp4-job-1" annexb)]
      (testing "status transitions :pending -> :decoded (final entity reports :decoded;
                the transient :pending write is internal to decode-frame!, see
                utsushi.codec-store-test for a direct assertion on that transact)"
        (is (= :decoded (:decode/status job)))
        (is (= :h264 (:decode/codec job))))
      (testing "dimensions from the container's own avcC-embedded SPS, persisted through the Store"
        (is (= 32 (:width (:decode/frame-blob job))))
        (is (= 32 (:height (:decode/frame-blob job)))))
      (testing "persisted luma plane is bit-exact vs. real ffmpeg's OWN decode of the same MP4 file
                (the same reference utsushi.pipeline.mp4-h264-test's
                decode-first-h264-frame-real-mp4-golden-vector validates against)"
        (is (= ref (:luma (:decode/frame-blob job)))))
      (testing "cross-check: the Store-persisted result is identical to
                decode-first-h264-frame's own (non-persisted) result for the
                SAME mp4 bytes — same Annex B bytes decoded twice, once
                outside the Store and once through it, agree exactly"
        (is (= (pipeline/decode-first-h264-frame mp4-bytes) (:decode/frame-blob job))))
      (testing "job is re-fetchable via job-entity after the fact (not just the return value)"
        (is (= job (store/job-entity conn "mp4-job-1" {})))))))
