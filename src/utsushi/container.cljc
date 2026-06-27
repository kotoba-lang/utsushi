(ns utsushi.container
  "① コンテナ層。ISO BMFF (MP4/MOV) の box ツリーを純 cljc で読む。

  設計: ADR-2606272200 §1。最終形は kasane.decode + `grammar/mp4.edn` に委譲するが、R0
  立ち上げのため最小の box スキャナをここに置く。`mdat` 等の payload（メディア実体）は
  decode せず box の [start,size) 境界だけ返す（実体の blob/CID 化は呼び出し側）。"
  (:require [utsushi.bytes :as b]))

;; 子 box を内包する container box type（中身を再帰スキャンする）。leaf は再帰しない。
(def container-box?
  #{"moov" "trak" "mdia" "minf" "stbl" "dinf" "edts"
    "udta" "mvex" "moof" "traf" "mfra" "meta" "ipro" "sinf"})

(defn parse-boxes
  "byte-vector `b` の [start,end) 区間の box を oldest-first で返す。各 box は
  {:type :start :size :header (:children)}。
  size==1→64bit largesize(header 16) / size==0→EOF まで / container は :children に再帰。"
  ([b] (parse-boxes b 0 (count b)))
  ([b start end]
   (loop [pos start, acc []]
     (if (> (+ pos 8) end)
       acc
       (let [size32 (b/u32 b pos)
             type   (b/ascii4 b (+ pos 4))
             [hdr size] (cond
                          (= size32 1) [16 (b/u64 b (+ pos 8))]
                          (= size32 0) [8  (- end pos)]
                          :else        [8  size32])
             box-end (+ pos size)
             base    {:type type :start pos :size size :header hdr}
             box     (if (container-box? type)
                       (assoc base :children (parse-boxes b (+ pos hdr) box-end))
                       base)]
         (if (or (< size 8) (> box-end end))
           (conj acc box)
           (recur box-end (conj acc box))))))))

(defn find-box
  "box ツリーから最初に `type` に一致する box を深さ優先で返す（無ければ nil）。"
  [boxes type]
  (some (fn [{t :type ch :children :as box}]
          (cond (= t type) box
                ch          (find-box ch type)
                :else       nil))
        boxes))

(defn leaf-payload
  "box の payload バイト（box header 後 ~ box 末尾）を subvec で返す。
  ISO のフルボックスは payload 先頭 4byte が version(1)+flags(3)。`buf` は vector 前提。"
  [buf {:keys [start size header]}]
  (subvec buf (+ start header) (+ start size)))
