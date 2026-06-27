(ns utsushi.container-test
  "utsushi.container の最小スモーク。合成 box でツリー構造と境界を検証する。"
  (:require [utsushi.container :as c]))

(defn- be32 [n]
  [(bit-and (bit-shift-right n 24) 0xff)
   (bit-and (bit-shift-right n 16) 0xff)
   (bit-and (bit-shift-right n 8) 0xff)
   (bit-and n 0xff)])

(defn- ascii [s] (mapv int s))

(defn- box [type payload]
  (let [size (+ 8 (count payload))]
    (vec (concat (be32 size) (ascii type) payload))))

;; ftyp(8B header + 8B body) と、moov>trak(子) を持つ合成 MP4。
(def sample
  (vec (concat (box "ftyp" (ascii "isom"))
               (box "moov" (box "trak" [])))))

(defn run []
  (let [boxes (c/parse-boxes sample)
        types (map :type boxes)
        moov  (c/find-box boxes "moov")
        trak  (c/find-box boxes "trak")]
    (assert (= types ["ftyp" "moov"]) (str "top-level types: " (vec types)))
    (assert (= 1 (count (:children moov))) "moov has 1 child")
    (assert (= "trak" (:type trak)) "trak found via deep search")
    (assert (nil? (c/find-box boxes "mdat")) "no mdat in sample")
    :ok))

(comment (run))
