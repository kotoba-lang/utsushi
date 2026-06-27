(ns utsushi.demux
  "① demux: MP4 box ツリー + buffer から track ごとの sample(packet) を取り出す。

  設計: ADR-2606272200 §1/§5。stbl のサンプルテーブル(stts/stsz/stsc/stco/co64/stss)を読み、
  各 sample の絶対オフセット/サイズ/pts/keyframe を復元する。sample bytes は content-id
  （utsushi.blob, R0 placeholder）で参照化する — 生バイトを言語境界で跨がせない設計の起点。
  stsd（codec config）は opaque な raw box bytes として保持し remux で verbatim 再emit する。"
  (:require [utsushi.bytes :as b]
            [utsushi.container :as c]
            [utsushi.blob :as blob]))

;; --- フルボックス payload からテーブルを読む（payload[0..3]=version+flags） ---

(defn- parse-stts [p]
  (let [n (b/u32 p 4)]
    (loop [i 0, off 8, acc []]
      (if (>= i n) acc
          (recur (inc i) (+ off 8)
                 (into acc (repeat (b/u32 p off) (b/u32 p (+ off 4)))))))))

(defn- parse-stsz [p]
  (let [sample-size  (b/u32 p 4)
        sample-count (b/u32 p 8)]
    (if (pos? sample-size)
      (vec (repeat sample-count sample-size))
      (mapv #(b/u32 p (+ 12 (* 4 %))) (range sample-count)))))

(defn- parse-stco [p]
  (let [n (b/u32 p 4)] (mapv #(b/u32 p (+ 8 (* 4 %))) (range n))))

(defn- parse-co64 [p]
  (let [n (b/u32 p 4)] (mapv #(b/u64 p (+ 8 (* 8 %))) (range n))))

(defn- parse-stsc
  "stsc を chunk(1-based) ごとの samples-per-chunk に展開する。"
  [p num-chunks]
  (let [n (b/u32 p 4)
        entries (mapv #(let [o (+ 8 (* 12 %))]
                         {:first (b/u32 p o) :spc (b/u32 p (+ o 4))})
                      (range n))]
    (mapv (fn [chunk] (:spc (last (filter #(<= (:first %) chunk) entries))))
          (range 1 (inc num-chunks)))))

(defn- parse-stss [p]
  (let [n (b/u32 p 4)] (set (map #(b/u32 p (+ 8 (* 4 %))) (range n)))))

(defn- build-samples
  "chunk-offsets/spc/sizes/deltas/sync から sample 列を組む。chunk 内は連続配置。
  sync=nil は『全 sample が keyframe』を意味する（stss 不在）。"
  [{:keys [chunk-offsets spc sizes deltas sync]}]
  (loop [chunks (map vector chunk-offsets spc), si 0, pts 0, acc []]
    (if (empty? chunks)
      acc
      (let [[off n] (first chunks)
            [acc* si* pts* _]
            (reduce (fn [[a i p o] _]
                      (let [sz (nth sizes i)
                            d  (nth deltas i)]
                        [(conj a {:idx i :offset o :size sz :pts p :duration d
                                  :keyframe (or (nil? sync) (contains? sync (inc i)))})
                         (inc i) (+ p d) (+ o sz)]))
                    [acc si pts off]
                    (range n))]
        (recur (rest chunks) si* pts* acc*)))))

(defn- trak->track [buf trak]
  (let [tk   [trak]
        lp   #(c/leaf-payload buf %)
        tkhd (c/find-box tk "tkhd")
        mdhd (c/find-box tk "mdhd")
        hdlr (c/find-box tk "hdlr")
        stsd (c/find-box tk "stsd")
        stts (c/find-box tk "stts")
        stsz (c/find-box tk "stsz")
        stsc (c/find-box tk "stsc")
        stco (c/find-box tk "stco")
        co64 (c/find-box tk "co64")
        stss (c/find-box tk "stss")
        offsets (if stco (parse-stco (lp stco)) (parse-co64 (lp co64)))
        spc     (parse-stsc (lp stsc) (count offsets))
        sizes   (parse-stsz (lp stsz))
        deltas  (parse-stts (lp stts))
        sync    (when stss (parse-stss (lp stss)))
        samples (->> (build-samples {:chunk-offsets offsets :spc spc
                                     :sizes sizes :deltas deltas :sync sync})
                     (mapv (fn [s]
                             (let [bytes (subvec buf (:offset s) (+ (:offset s) (:size s)))]
                               (assoc s :bytes bytes :cid (blob/content-id bytes))))))]
    {:track-id  (b/u32 (lp tkhd) 12)
     :handler   (b/ascii4 (lp hdlr) 8)
     :timescale (b/u32 (lp mdhd) 12)
     :stsd      (subvec buf (:start stsd) (+ (:start stsd) (:size stsd)))
     :samples   samples}))

(defn demux
  "MP4 byte 列を {:timescale :tracks [track...]} に分解する。各 track は
  {:track-id :handler :timescale :stsd(raw bytes) :samples [{:idx :offset :size :pts
  :duration :keyframe :bytes :cid}...]}。"
  [buf]
  (let [buf   (vec buf)
        boxes (c/parse-boxes buf)
        moov  (c/find-box boxes "moov")
        traks (filter #(= "trak" (:type %)) (:children moov))
        tracks (mapv #(trak->track buf %) traks)]
    {:timescale (:timescale (first tracks))
     :tracks    tracks}))
