(ns utsushi.remux
  "再エンコードなしの編集（trim / concat）。demux 構造を変換し utsushi.mux で再 mux する。

  設計: ADR-2606272200 §1/§4。codec は触らない（sample bytes/cid を保持）。これらは filtergraph
  (utsushi.graph) の :demux/:remux ノードが扱う純粋変換 = ③ codec を介さない経路。"
  (:require [utsushi.mux :as mux]))

(defn- track-duration [track]
  (reduce + 0 (map :duration (:samples track))))

(defn trim
  "各 track の sample を pts ∈ [pts-start, pts-end) に絞る（再エンコードなし、純粋な sample 選択）。
  keyframe 境界への丸めは R0 では行わない。pts は demux 由来の絶対値で判定する。"
  [demuxed pts-start pts-end]
  (update demuxed :tracks
          (fn [tracks]
            (mapv (fn [t]
                    (update t :samples
                            (fn [ss]
                              (vec (filter #(and (>= (:pts %) pts-start)
                                                 (< (:pts %) pts-end))
                                           ss)))))
                  tracks))))

(defn concat-streams
  "2 つの demux 構造を track ごとに連結（同じ track 並び・互換 stsd 前提, R0）。
  後段 sample の pts を前段 track の総 duration ぶんシフトする。"
  [a b]
  (assoc a :tracks
         (mapv (fn [ta tb]
                 (let [dur (track-duration ta)
                       shifted (mapv #(update % :pts + dur) (:samples tb))]
                   (update ta :samples into shifted)))
               (:tracks a) (:tracks b))))

(defn remux
  "demux 構造をそのまま（または trim/concat 後に）MP4 byte 列へ書き戻す薄いエイリアス。"
  [demuxed]
  (mux/mux demuxed))
