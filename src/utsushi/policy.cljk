(ns utsushi.policy
  "R1 capability / effect / gas モデル — kotoba `policy.rs`(deny-by-default CapClass) /
  `effects.rs`(effect soundness T2) / fuel の cljc 鏡写し（ADR-2606272200 §3）。

  filtergraph の各 node は effect を持つ（codec node = :media-decode/:media-encode、
  demux/trim/mux 等の純コンテナ操作 = nil）。実行前に:
    1) node effect ⊆ graph 宣言 :effects（under-declaration 禁止 = T2 相当）
    2) node effect ∈ policy :granted（deny-by-default）
  を検査し、実行中は per-frame gas 会計で総コストを上限拘束する（fuel 相当）。

  R1 追加（2026-07-30、ADR-2800002800）: codec op は **どの backend が走るか**まで
  検査する（`utsushi.backend`）。理由は 2 つ。

  1. **platform で実行可能性が変わる。** `:encode` を browser で走らせる graph は、
     組んだ時点では通って実行時に落ちていた。`check-graph` が backend の有無を
     見れば、落ちるのは graph を組んだ時点になり、理由（platform / status /
     codec 非対応のどれか）も具体的に出る。
  2. **同じ op のコストが backend で 3 桁違う。** 実測で :cljc の H.264 encode が
     18,140 ms/frame、ffmpeg が 16 ms/frame。gas を op だけで決めると、どちらが
     走るかによって見積りが 1000 倍外れる。`graph-gas` は選ばれた backend の
     実測倍率を掛ける。"
  (:require [utsushi.backend :as backend]))

(def known-effects #{:media-decode :media-encode})

;; op → 誘発する effect（nil = 純粋, 権限不要）
;; scale/pad/fit/hold/zoompan は `utsushi.raster` の画素演算で、entropy coding も
;; native 呼び出しも含まないので effect は nil。ここに effect を付けると
;; deny-by-default が「画素を触るだけの graph」まで拒否してしまい、逆に
;; :media-decode を付けると effect モデルが嘘になる（実際には何も decode しない）。
(def effect-of-op
  {:demux nil :trim nil :concat nil :mux nil
   :scale nil :pad nil :fit nil :hold nil :zoompan nil
   :decode :media-decode :encode :media-encode})

;; op の基本 gas。decode/encode と画素 filter は per-frame に乗算する。
;; filter の gas は decode/encode より 1 桁小さい — 実測（2026-07-30）で
;; 720x1280 の bilinear scale が 67 ms、同フレームの H.264 encode が 1,226 ms。
(def gas-cost
  {:demux 50 :trim 10 :concat 20 :mux 50
   :scale 100 :pad 40 :fit 140 :hold 5 :zoompan 120
   :decode 1000 :encode 1500})

;; per-frame に gas が乗る op。画素 filter はフレームごとに走るので当然含む —
;; ここから漏らすと 1800 フレームの graph が 1 フレーム分の見積りで通ってしまう。
(def per-frame? #{:decode :encode :scale :pad :fit :zoompan})

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
                            {:node id :codec (:codec args) :kind :codec-denied})))
          ;; backend の実行可能性。`backend/select` は却下理由を全部添えて投げる。
          ;; ここで落とすと「graph を組んだ時点」で失敗するので、実行時に
          ;; VideoEncoder undefined を踏むより早く、原因も具体的である。
          ;;
          ;; 既定では façade op（opaque passthrough + metadata）として検査する ——
          ;; それが `utsushi.codec` が実際にやっていることで、任意の codec に対して
          ;; 成立する。`:require-pixel-backend? true` を渡したときだけ、この graph の
          ;; :decode/:encode を**実画素**処理として検査する。既定を画素にすると
          ;; 「:h265 を opaque に通す」既存契約を壊す（実際に壊した）。
          (when (contains? backend/facade-ops op)
            (let [plat (or (:platform policy) (backend/platform))]
              (backend/select op (:codec args) policy plat)
              (when (:require-pixel-backend? policy)
                (backend/select (if (= op :encode) :encode-pixels :decode-pixels)
                                (:codec args) policy plat)))))))
    graph))

(defn node-gas
  "1 node の gas。`per-frame?` の op は `frames` を掛け、codec op はさらに
  選ばれた backend の実測倍率（`backend/gas-scale`）を掛ける。

  倍率を掛けるのが本質的な変更である —— `gas-cost` の `:encode 1500` は
  「純 cljc で 1 フレーム encode する」コストの単位であり、hardware backend が
  走るなら同じ node の実コストは 3 桁小さい。倍率を無視した見積りは、
  どちらが走るかによって 1000 倍外れる。"
  [{:keys [op args]} frames policy]
  (let [base (get gas-cost op 0)
        n (if (contains? per-frame? op) (max 1 frames) 1)
        ;; 倍率は**画素 backend** のもの。façade op（opaque）は画素を触らないので
        ;; 倍率の対象ではない —— :require-pixel-backend? が立っているときだけ、
        ;; この graph の codec node が実画素処理だと分かるので倍率を掛ける。
        scale (if (and (:require-pixel-backend? policy) (contains? backend/facade-ops op))
                (backend/gas-scale
                  (backend/select (if (= op :encode) :encode-pixels :decode-pixels)
                                  (:codec args) policy
                                  (or (:platform policy) (backend/platform))))
                1.0)
        ;; ceil を interop で書くと cljs が死ぬ（この ns は browser でも動く）
        x (* base n scale)
        f #?(:clj (long x) :cljs (js/Math.floor x))]
    (if (== f x) (long f) (long (inc f)))))

(defn graph-gas
  "graph 全体の gas 見積り（`frames` フレームを流したときの総和）。
  `:gas-limit` を超えるなら ex-info を投げる —— 実行して fuel を尽かすのではなく、
  組んだ時点で拒否する。"
  [graph policy frames]
  (let [total (reduce + 0 (map #(node-gas % frames policy) (:nodes graph)))
        limit (:gas-limit policy)]
    (when (and limit (> total limit))
      (throw (ex-info (str "gas 見積り " total " が上限 " limit " を超える")
                      {:kind :gas-limit-exceeded :estimate total :limit limit
                       :frames frames})))
    total))
