(ns utsushi.pregel
  "④ filtergraph を BSP（Pregel 風）superstep で **決定論的**に実行する。kotoba `defgraph` +
  Pregel BSP の cljc 実現（ADR-2606272200 §4）。

  - 各 node = vertex。topological layer ごとに 1 superstep、layer 内は id ソートで決定論的。
  - frame/packet は CID 参照で流れる（生バイトは demuxed 内に保持、境界越えは CID）。
  - `filtergraph-cid` = topology の content-id（= kotoba graph_def_cid 相当、クロージャ非含有）。
  - capability/effect は実行前に utsushi.policy/check-graph で検査（deny-by-default + T2）。
  - per-frame gas 会計で総コストを上限拘束（fuel 相当）。
  - 同一 input-cid + 同一 filtergraph-cid → 同一 output、`transcode` が CID-MV cache でメモ化。

  コンテナ操作（demux/remux/bytes/blob）は org-iso-isobmff へ抽出済み（kotoba-lang
  reverse-domain media/graphics standards-substrate split, com-junkawasaki/root
  ADR 前例2607072500）— utsushi はそれに依存する filtergraph オーケストレータになる。"
  (:require [isobmff.bytes :as b]
            [isobmff.blob :as blob]
            [isobmff.demux :as demux]
            [isobmff.remux :as remux]
            [utsushi.codec :as codec]
            [utsushi.policy :as policy]))

(defn filtergraph-cid
  "topology（sorted nodes の id/op/args/effect + sorted edges + sorted effects）の content-id。
  実行クロージャは含めない（同一トポロジ → 同一 CID）。"
  [graph]
  (let [norm (pr-str {:effects (sort (map str (:effects graph)))
                      :nodes (sort-by #(str (:id %))
                                      (map #(select-keys % [:id :op :args :effect]) (:nodes graph)))
                      :edges (sort (map (comp vec (partial map str)) (:edges graph)))})]
    (blob/content-id (b/wstr norm))))

(defn- topo-layers
  "edges から topological layer 列（各 layer は id ソート済み vector）を返す。cycle は例外。"
  [nodes edges]
  (let [ids   (set (map :id nodes))
        preds (reduce (fn [m [from to]] (update m to (fnil conj #{}) from)) {} edges)]
    (loop [remaining ids, done #{}, layers []]
      (if (empty? remaining)
        layers
        (let [ready (->> remaining
                         (filter #(every? done (get preds % #{})))
                         (sort-by str) vec)]
          (when (empty? ready)
            (throw (ex-info "filtergraph has a cycle" {:remaining remaining})))
          (recur (reduce disj remaining ready) (into done ready) (conj layers ready)))))))

(def ^:private op-fn
  {:demux  (fn [_pol _node in]  (demux/demux (:bytes in)))
   :trim   (fn [_pol node in]   (remux/trim in (-> node :args :start) (-> node :args :end)))
   :concat (fn [_pol _node ins] (reduce remux/concat-streams ins))
   :decode (fn [pol node in]    (codec/decode pol (-> node :args :codec) in))
   :encode (fn [pol node in]    (codec/encode pol (-> node :args :codec) (:args node) in))
   :mux    (fn [_pol _node in]  (let [by (remux/remux in)] {:bytes by :cid (blob/content-id by)}))})

(defn run
  "filtergraph を input（{:bytes :cid} か demux 構造）から実行する。
  返り値 {:output :output-cid :graph-cid :supersteps :gas}。"
  [graph input policy]
  (policy/check-graph graph policy)
  (let [by-id  (into {} (map (juxt :id identity) (:nodes graph)))
        preds  (reduce (fn [m [from to]] (update m to (fnil conj []) from)) {} (:edges graph))
        layers (topo-layers (:nodes graph) (:edges graph))
        gcid   (filtergraph-cid graph)]
    (loop [ls layers, outs {}, gas 0, steps 0]
      (if (empty? ls)
        (let [sink (last (last layers))
              out  (get outs sink)]
          {:output out :output-cid (:cid out) :graph-cid gcid :supersteps steps :gas gas})
        (let [[outs* gas*]
              (reduce
               (fn [[o g] id]
                 (let [node (by-id id)
                       p    (get preds id [])
                       in   (cond (empty? p)        input
                                  (= 1 (count p))   (get o (first p))
                                  :else             (mapv #(get o %) p))
                       fc   (if (policy/per-frame? (:op node)) (codec/frame-count in) 1)
                       cost (* (get policy/gas-cost (:op node) 1) fc)
                       g2   (+ g cost)]
                   (when (> g2 (:gas-limit policy))
                     (throw (ex-info (str "out of gas at node " id) {:node id :gas g2 :kind :out-of-gas})))
                   [(assoc o id ((op-fn (:op node)) policy node in)) g2]))
               [outs gas] (first ls))]
          (recur (rest ls) outs* gas* (inc steps)))))))

(defn transcode
  "run + content-addressed メモ化。`cache` は atom（{[input-cid graph-cid] result}）。
  同一 input-cid + 同一 filtergraph-cid なら再計算せず cache から返す（ADR §4 CID-MV cache）。"
  [graph input policy cache]
  (let [icid (or (:cid input) (blob/content-id (:bytes input)))
        gcid (filtergraph-cid graph)
        k    [icid gcid]]
    (if-let [hit (get @cache k)]
      (assoc hit :cached true)
      (let [res (assoc (run graph input policy) :cached false)]
        (swap! cache assoc k res)
        res))))
