(ns utsushi.policy
  "R1 capability / effect / gas モデル — kotoba `policy.rs`(deny-by-default CapClass) /
  `effects.rs`(effect soundness T2) / fuel の cljc 鏡写し（ADR-2606272200 §3）。

  filtergraph の各 node は effect を持つ（codec node = :media-decode/:media-encode、
  demux/trim/mux 等の純コンテナ操作 = nil）。実行前に:
    1) node effect ⊆ graph 宣言 :effects（under-declaration 禁止 = T2 相当）
    2) node effect ∈ policy :granted（deny-by-default）
  を検査し、実行中は per-frame gas 会計で総コストを上限拘束する（fuel 相当）。")

(def known-effects #{:media-decode :media-encode})

;; op → 誘発する effect（nil = 純粋, 権限不要）
(def effect-of-op
  {:demux nil :trim nil :concat nil :mux nil
   :decode :media-decode :encode :media-encode})

;; op の基本 gas。decode/encode は per-frame に乗算する。
(def gas-cost
  {:demux 50 :trim 10 :concat 20 :mux 50 :decode 1000 :encode 1500})

(def per-frame? #{:decode :encode})

(defn deny-all
  "全 capability 不許可・既定 gas 上限のみの完全封じ込めポリシー。"
  []
  {:granted #{} :codecs #{} :gas-limit 10000000})

(defn grant
  "effect（と任意で codec 許可リスト）を付与する。"
  [policy effect & codecs]
  (-> policy
      (update :granted conj effect)
      (update :codecs into codecs)))

(defn with-gas-limit [policy n] (assoc policy :gas-limit n))

(defn check-graph
  "filtergraph の effect 健全性 + capability を検査。違反は ex-info を投げ、コンパイル/実行を
  止める（kotoba: import が allowlist の部分集合でなければ module を emit しない の相当）。"
  [graph policy]
  (let [declared (:effects graph #{})]
    (doseq [{:keys [id op args effect]} (:nodes graph)]
      (let [eff (or effect (effect-of-op op))]
        (when eff
          (when-not (contains? declared eff)
            (throw (ex-info (str "effect soundness: node " id " performs " eff
                                 " not in graph :effects " declared " (T2)")
                            {:node id :effect eff :kind :under-declaration})))
          (when-not (contains? (:granted policy) eff)
            (throw (ex-info (str "capability denied: node " id " needs " eff
                                 " (deny-by-default)")
                            {:node id :effect eff :kind :capability-denied})))
          (when (and (:codec args) (seq (:codecs policy))
                     (not (contains? (:codecs policy) (:codec args))))
            (throw (ex-info (str "codec not granted: " (:codec args) " at node " id)
                            {:node id :codec (:codec args) :kind :codec-denied}))))))
    graph))
