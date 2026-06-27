(ns utsushi.graph
  "④ filtergraph オーケストレーション層。

  設計: ADR-2606272200 §4。ffmpeg の -filter_complex（scale→fps→overlay→encode の DAG）は
  kotoba の `defgraph` と同型。各 filter = Pregel の 1 vertex、frame = channel state（実体は
  CID）、graph_def_cid = ジョブ ID。同一入力 CID + 同一 filtergraph CID → 同一出力 CID で
  CID-MV cache がトランスコードのメモ化になる。

  ここでは『filtergraph をデータ（EDN）として組み立てる』ヘルパだけを純 cljc で持つ。
  実際の defgraph マクロ展開と Pregel 実行は kotoba 側（kotoba-vm）で行う — この EDN を
  defgraph 定義へ射影する。")

(defn filtergraph
  "filtergraph を正規化 EDN で組み立てる。
  nodes: [{:id kw :op (...) }...]、edges: [[from to]...]、effects: #{:media-decode ...}。
  返り値は kotoba defgraph 定義へ射影可能なデータ。"
  [{:keys [nodes edges effects] :or {effects #{}}}]
  {:utsushi/filtergraph true
   :effects effects
   :nodes   (vec nodes)
   :edges   (vec edges)})

(comment
  ;; 例（ADR §4）: H.264 → 720p → H.265
  (filtergraph
   {:effects #{:media-decode :media-encode}
    :nodes [{:id :demux  :op '(uts/demux input-cid)}
            {:id :decode :op '(uts/decode :h264 (:packets %))}
            {:id :scale  :op '(uts/vf-scale 1280 720 (:frames %))}
            {:id :encode :op '(uts/encode :h265 {:crf 23} (:frames %))}]
    :edges [[:demux :decode] [:decode :scale] [:scale :encode]]}))
