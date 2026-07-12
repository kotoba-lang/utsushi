(ns utsushi.codec-encode-test
  "H.264 :params → avcC :stsd encode wiring (Wave 4, ADR-2607121400), and
   the encode→decode round trip through utsushi.codec's own decode path —
   the strongest correctness signal available without a reference decoder
   or real MP4 muxing (out of scope: this exercises codec/encode and
   codec/decode directly on a synthetic demuxed structure, not a real MP4
   file)."
  (:require [clojure.test :refer [deftest is testing]]
            [utsushi.codec :as codec]))

(def ^:private baseline-params
  {:profile-idc 66 :level-idc 30 :width 96 :height 64})

(defn- demuxed-with-params [params]
  {:stage :demuxed
   :tracks [{:handler "vide" :params params}
            {:handler "soun"}]}) ; non-video track must be left untouched

(deftest h264-encode-produces-avcc-stsd
  (let [encoded (codec/encode nil :h264 nil (demuxed-with-params baseline-params))
        vide    (first (filter #(= (:handler %) "vide") (:tracks encoded)))
        soun    (first (filter #(= (:handler %) "soun") (:tracks encoded)))]
    (testing "encode stage transition"
      (is (= :encoded (:stage encoded))))
    (testing "video track gains a real :stsd byte vector"
      (is (vector? (:stsd vide)))
      (is (pos? (count (:stsd vide)))))
    (testing "non-video track is untouched (no :stsd synthesized)"
      (is (nil? (:stsd soun))))))

(deftest h264-encode-decode-round-trip
  (let [encoded (codec/encode nil :h264 nil (demuxed-with-params baseline-params))
        decoded (codec/decode nil :h264 encoded)
        vide    (first (filter #(= (:handler %) "vide") (:tracks decoded)))]
    (testing "decode recovers exactly the params encode was given"
      (is (= baseline-params (select-keys (:params vide) [:profile-idc :level-idc :width :height]))))
    (testing "decode also recovers chroma-format-idc default (4:2:0) for non-high-profile"
      (is (= 1 (get-in vide [:params :chroma-format-idc]))))))

(deftest h264-encode-non-h264-codec-stays-opaque
  (let [d       (demuxed-with-params baseline-params)
        encoded (codec/encode nil :h265 nil d)
        vide    (first (filter #(= (:handler %) "vide") (:tracks encoded)))]
    (is (= :encoded (:stage encoded)))
    (is (nil? (:stsd vide)) "no avcC synthesis attempted for a non-:h264 codec arg")))

(deftest h264-encode-non-multiple-of-16-dimensions-throws
  (testing "org-iso-h264's frame_cropping-less encode scope limit surfaces here too"
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (codec/encode nil :h264 nil
                               (demuxed-with-params (assoc baseline-params :width 100)))))))
