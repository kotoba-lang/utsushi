(ns utsushi.graph
  "④ filtergraph オーケストレーション層（データ構築）。

  設計: ADR-2606272200 §4。ffmpeg の -filter_complex DAG = kotoba `defgraph` と同型。ここでは
  filtergraph を正規化データ（EDN）として組み立てる。実行は utsushi.pregel（BSP）が行い、
  最終的に kotoba defgraph + Pregel BSP へ射影する。

  node: {:id kw :op kw :args map :effect kw|nil}
  edge: [from-id to-id]
  graph: {:effects #{...} :nodes [...] :edges [...]}"
  (:require [utsushi.policy :as policy]))

(defn filtergraph
  "filtergraph を正規化する。各 node の :effect は未指定なら op から補完する。"
  [{:keys [effects nodes edges] :or {effects #{}}}]
  {:effects effects
   :nodes (mapv (fn [n] (update n :effect #(or % (policy/effect-of-op (:op n))))) nodes)
   :edges (mapv vec edges)})

(comment
  ;; 例（ADR §4）: H.264 → trim → H.265 transcode
  (filtergraph
   {:effects #{:media-decode :media-encode}
    :nodes [{:id :demux  :op :demux}
            {:id :decode :op :decode :args {:codec :h264}}
            {:id :encode :op :encode :args {:codec :h265 :crf 23}}
            {:id :mux    :op :mux}]
    :edges [[:demux :decode] [:decode :encode] [:encode :mux]]}))
