(ns utsushi.container
  "① コンテナ demux/mux 層。ISO BMFF (MP4/MOV) の box ツリーを純 cljc で読む。

  設計: ADR-2606272200 §1。最終形は kasane.decode + `grammar/mp4.edn`（EDN 文法 DSL）
  に委譲するが、R0 立ち上げのため最小の手書き box スキャナをここに置く。

  不変条件: `mdat` 等の payload（メディア実体）は decode せず、box の [start,size)
  境界だけ返す。実体の blob/CID 化は呼び出し側（utsushi.quads / Vault）が行う
  （ADR §1/§5 — 生フレームを言語境界で跨がせない）。")

;; 子 box を内包する container box type（中身を再帰スキャンする）。
;; ISO/IEC 14496-12 の代表的な container box。leaf（mdat/free/ftyp 等）は再帰しない。
(def container-box?
  #{"moov" "trak" "mdia" "minf" "stbl" "dinf" "edts"
    "udta" "mvex" "moof" "traf" "mfra" "meta" "ipro" "sinf"})

(defn- u32 [b i]
  (bit-or (bit-shift-left (bit-and (int (nth b i)) 0xff) 24)
          (bit-shift-left (bit-and (int (nth b (+ i 1))) 0xff) 16)
          (bit-shift-left (bit-and (int (nth b (+ i 2))) 0xff) 8)
          (bit-and (int (nth b (+ i 3))) 0xff)))

(defn- u64 [b i]
  (+ (* (u32 b i) 4294967296) (u32 b (+ i 4))))

(defn- box-type [b i]
  (apply str (map #(char (bit-and (int (nth b (+ i %))) 0xff)) (range 4))))

(defn parse-boxes
  "byte-indexable な `b`（vector/array of bytes）の [start,end) 区間の box を
  oldest-first で返す。各 box は {:type :start :size :header (:children)}。

  - size==1 → 64-bit largesize（header 16B）
  - size==0 → このトラック末尾（EOF まで）
  - container-box は :children に再帰結果を持つ
  truncated/garbage を踏んだら、その box を含めて打ち切る（best-effort）。"
  ([b] (parse-boxes b 0 (count b)))
  ([b start end]
   (loop [pos start, acc []]
     (if (> (+ pos 8) end)
       acc
       (let [size32 (u32 b pos)
             type   (box-type b (+ pos 4))
             [hdr size] (cond
                          (= size32 1) [16 (u64 b (+ pos 8))]  ; 64-bit largesize
                          (= size32 0) [8  (- end pos)]         ; to EOF
                          :else        [8  size32])
             box-end (+ pos size)
             base    {:type type :start pos :size size :header hdr}
             box     (if (container-box? type)
                       (assoc base :children (parse-boxes b (+ pos hdr) box-end))
                       base)]
         (if (or (< size 8) (> box-end end))
           (conj acc box)                 ; truncated → stop here
           (recur box-end (conj acc box))))))))

(defn find-box
  "box ツリーから最初に `type` に一致する box を深さ優先で返す（無ければ nil）。"
  [boxes type]
  (some (fn [{t :type ch :children :as box}]
          (cond (= t type) box
                ch          (find-box ch type)
                :else       nil))
        boxes))

;; TODO(R0):
;;  - stbl のサンプルテーブル(stsz/stco/stsc/stts) を読み、elementary stream の
;;    パケット境界（offset+size+pts）を返す demux を足す。
;;  - remux（trim/concat）= box ツリー再構築 + offset 再計算（再エンコードなし）。
;;  - mp4 以外（mkv=EBML / ts / wav / ogg）は grammar/*.edn + kasane.decode で。
