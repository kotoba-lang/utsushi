(ns utsushi.e2e-test
  "R0 E2E: mux → demux → (trim / concat) → remux → demux の round-trip 不変条件で
  demux + content-addressing + 再エンコードなし編集 + offset/テーブル再計算を検証する。
  外部メディアファイル/プレーヤー不要（mux と demux を相互検証する）。"
  (:require [utsushi.mux :as mux]
            [utsushi.demux :as demux]
            [utsushi.blob :as blob]
            [utsushi.remux :as remux]))

(defn- sample [byte-val n dur keyframe?]
  (let [bytes (vec (repeat n byte-val))]
    {:bytes bytes :size n :duration dur :keyframe keyframe?}))

;; video: 5 samples, dur 100, keyframe@idx0,3 → pts 0,100,200,300,400
(def track-v
  {:track-id 1 :handler "vide" :timescale 1000
   :stsd (mux/minimal-stsd "avc1")
   :samples [(sample 0x11 20 100 true)
             (sample 0x22 10 100 false)
             (sample 0x33 30 100 false)
             (sample 0x44 15 100 true)
             (sample 0x55 25 100 false)]})

;; audio: 5 samples, dur 80, all keyframe → pts 0,80,160,240,320
(def track-a
  {:track-id 2 :handler "soun" :timescale 1000
   :stsd (mux/minimal-stsd "mp4a")
   :samples (mapv #(sample (+ 0xA0 %) (+ 8 %) 80 true) (range 5))})

(def src (mux/mux {:tracks [track-v track-a] :timescale 1000}))

(defn- sig
  "track の sample から (size,duration,keyframe,cid) の検証用シグネチャ列を作る。"
  [track]
  (mapv (juxt :size :duration :keyframe :cid) (:samples track)))

(defn- expected-sig [track]
  ;; mux 入力 track（cid 無し）から期待シグネチャを作る
  (mapv (fn [s] [(:size s) (:duration s) (:keyframe s) (blob/content-id (:bytes s))])
        (:samples track)))

(defn run []
  ;; --- 1) demux が source を正しく復元する ---
  (let [d (demux/demux src)
        [dv da] (:tracks d)]
    (assert (= 2 (count (:tracks d))) "2 tracks")
    (assert (= "vide" (:handler dv)) "video handler")
    (assert (= "soun" (:handler da)) "audio handler")
    (assert (= [0 100 200 300 400] (mapv :pts (:samples dv))) "video pts")
    (assert (= [0 80 160 240 320] (mapv :pts (:samples da))) "audio pts")
    (assert (= [true false false true false] (mapv :keyframe (:samples dv))) "video keyframes")
    (assert (every? :keyframe (:samples da)) "audio all sync")
    (assert (= (expected-sig track-v) (sig dv)) "video sig round-trip (size/dur/kf/cid)")
    (assert (= (expected-sig track-a) (sig da)) "audio sig round-trip")
    ;; sample bytes が完全一致（cid と bytes の両方）
    (assert (= (mapv :bytes (:samples track-v)) (mapv :bytes (:samples dv))) "video bytes verbatim")

    ;; --- 2) trim [200,400) → remux → demux で sample 集合が保たれる ---
    (let [t  (remux/trim d 200 400)
          re (remux/remux t)
          d2 (demux/demux re)
          [d2v d2a] (:tracks d2)]
      ;; video: pts 200,300 → idx2,3（2 件）/ audio: pts 240,320 → idx3,4（2 件）
      (assert (= 2 (count (:samples d2v))) "trim video → 2 samples")
      (assert (= 2 (count (:samples d2a))) "trim audio → 2 samples")
      ;; 残った sample の cid は元と一致（再エンコードしていない証拠）
      (assert (= [(blob/content-id (vec (repeat 30 0x33)))
                  (blob/content-id (vec (repeat 15 0x44)))]
                 (mapv :cid (:samples d2v))) "trim video cids preserved")
      ;; remux 後は pts が 0 起点で振り直される（mux は duration からテーブル再構築）
      (assert (= [0 100] (mapv :pts (:samples d2v))) "trim video pts re-based")
      ;; keyframe フラグも保たれる（idx2=false, idx3=true）
      (assert (= [false true] (mapv :keyframe (:samples d2v))) "trim video keyframes preserved"))

    ;; --- 3) concat(d, d) → remux → demux で各 track が 2 倍・前半後半 cid 一致 ---
    (let [cc (remux/concat-streams d d)
          re (remux/remux cc)
          d3 (demux/demux re)
          [d3v d3a] (:tracks d3)]
      (assert (= 10 (count (:samples d3v))) "concat video → 10 samples")
      (assert (= 10 (count (:samples d3a))) "concat audio → 10 samples")
      (assert (= (mapv :cid (take 5 (:samples d3v)))
                 (mapv :cid (drop 5 (:samples d3v)))) "concat video halves cid-equal")
      ;; pts が連続（後半は前半 duration ぶんシフト）: 0,100,...,400,500,...,900
      (assert (= (range 0 1000 100) (mapv :pts (:samples d3v))) "concat video pts contiguous")))
  :ok)

(comment (run))
