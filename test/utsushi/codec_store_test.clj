(ns utsushi.codec-store-test
  "Integration test for `utsushi.codec.store` (ADR-2607122000 §4/Migration
   手順5) against a REAL org-iso-h264 golden vector — the same
   `flat16-dc-only.h264` fixture (and its `ffmpeg`-decoded reference YUV)
   `org-iso-h264`'s own `h264.decode-test/flat16-dc-only-golden-vector`
   validates against, copied verbatim into this repo's
   `resources/utsushi/fixtures/` (see that repo's `test/h264/decode_test.clj`
   docstring for exact provenance: real libx264 baseline-profile encode of a
   flat 16x16 gray macroblock, decoded bit-exact by real ffmpeg for the
   reference).

   This test's job is NOT to re-validate `h264.decode/decode-idr-frame`'s
   pixel correctness (that's `org-iso-h264`'s own test suite's job — this
   namespace just calls it) — it validates that `utsushi.codec.store`
   correctly PERSISTS the decode job's state transitions
   (`:pending`→`:decoded`) and the ACTUAL decoded pixels through the
   `:db-api`-injected Store, using the default `langchain.db/api` backend
   (in-process, pure .cljc — no real Datomic connection needed here, same
   as `talent.store`'s `DatomicStore` test in `gftd-talent-actor`, which
   also validates its Datomic-shaped backend in-process against
   `langchain.db` without a live Datomic Local/Peer)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [langchain.db :as db]
            [utsushi.codec.store :as store]))

(defn- rd [p] (mapv #(bit-and (int %) 0xff)
                    (with-open [in (io/input-stream (io/resource p))] (.readAllBytes in))))

(defn- fresh-conn [] (db/create-conn store/decode-schema))

(deftest decode-frame-persists-pending-then-decoded
  (let [conn (fresh-conn)
        bytes (rd "utsushi/fixtures/flat16-dc-only.h264")
        ref (vec (take 256 (rd "utsushi/fixtures/flat16-dc-only.ref.yuv")))]
    (testing "job starts absent"
      (is (nil? (store/job-entity conn "job-1" {}))))
    (let [job (store/decode-frame! conn "job-1" bytes)]
      (testing "final job entity reports :decoded"
        (is (= :decoded (:decode/status job)))
        (is (= :h264 (:decode/codec job)))
        (is (= 0 (:decode/frame-num job)))
        (is (= 0 (:decode/poc job))))
      (testing "the persisted frame-blob carries the REAL reconstructed pixels,
                bit-exact against the same real-ffmpeg-decoded reference
                org-iso-h264's own golden-vector test uses"
        (is (= 16 (:width (:decode/frame-blob job))))
        (is (= 16 (:height (:decode/frame-blob job))))
        (is (= ref (:luma (:decode/frame-blob job)))))
      (testing "GOP descriptor recorded for this (single-frame) job"
        (is (= {:idr-frame-num 0} (:decode/gop job))))
      (testing "no reference frames / motion vectors for an IDR-only intra decode"
        (is (nil? (:decode/mv-field job)))
        (is (nil? (:decode/ref-frames job))))
      (testing "job is re-fetchable via job-entity after the fact (not just the return value)"
        (is (= job (store/job-entity conn "job-1" {})))))))

(deftest decode-frame-records-failed-status-on-a-corrupt-stream
  (let [conn (fresh-conn)
        garbage (vec (repeat 32 0))]
    (is (thrown? clojure.lang.ExceptionInfo (store/decode-frame! conn "job-bad" garbage)))
    (let [job (store/job-entity conn "job-bad" {})]
      (testing "status recorded as :failed even though decode-frame! threw"
        (is (= :failed (:decode/status job))))
      (testing "diagnostic error message persisted"
        (is (string? (:decode/error job)))))))

(deftest decode-frame-rejects-non-h264-codec
  (let [conn (fresh-conn)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"only :h264 is wired"
                          (store/decode-frame! conn "job-av1" [] {:codec :av1})))))
