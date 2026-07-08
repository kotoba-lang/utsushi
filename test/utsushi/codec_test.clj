(ns utsushi.codec-test
  "H.264 avcC → SPS wiring, validated against a real libx264-encoded MP4
   (`ffmpeg -f lavfi -i testsrc=size=96x64 ... -profile:v baseline`).
   `ffprobe` confirms the source is 96x64; utsushi.codec/decode should
   attach that exact width/height (read from the container's own avcC box,
   via org-iso-h264's RBSP+SPS parser) to the demuxed video track."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [isobmff.demux :as demux]
            [utsushi.codec :as codec]))

(defn- rd [p] (mapv #(bit-and (int %) 0xff)
                    (with-open [in (io/input-stream (io/resource p))] (.readAllBytes in))))

(deftest h264-avcc-sps-params
  (let [d       (demux/demux (rd "utsushi/fixtures/sample.mp4"))
        decoded (codec/decode nil :h264 d)
        vide    (first (filter #(= (:handler %) "vide") (:tracks decoded)))]
    (testing "decode stage transition unaffected"
      (is (= :decoded (:stage decoded))))
    (testing "avcC-embedded SPS decodes to the real 96x64 encode source"
      (is (= 96 (get-in vide [:params :width])))
      (is (= 64 (get-in vide [:params :height])))
      (is (= 66 (get-in vide [:params :profile-idc]))))))                 ; baseline

(deftest non-h264-codec-stays-opaque
  (let [d       (demux/demux (rd "utsushi/fixtures/sample.mp4"))
        decoded (codec/decode nil :h265 d)
        vide    (first (filter #(= (:handler %) "vide") (:tracks decoded)))]
    (is (= :decoded (:stage decoded)))
    (is (nil? (:params vide)) "no avcC parsing attempted for a non-:h264 codec arg")))
