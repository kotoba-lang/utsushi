(ns utsushi.pregel-test
  "R1 + Pregel E2E: filtergraph を BSP で決定論実行し、capability(deny-by-default)・
  effect soundness(T2)・gas(fuel)・content-addressed メモ化を検証する（ADR §3/§4）。"
  (:require [utsushi.mux :as mux]
            [utsushi.demux :as demux]
            [utsushi.blob :as blob]
            [utsushi.graph :as graph]
            [utsushi.policy :as policy]
            [utsushi.pregel :as pregel]))

(defn- sample [v n dur kf] {:bytes (vec (repeat n v)) :size n :duration dur :keyframe kf})

(def src
  (mux/mux
   {:timescale 1000
    :tracks [{:track-id 1 :handler "vide" :timescale 1000 :stsd (mux/minimal-stsd "avc1")
              :samples [(sample 0x11 20 100 true) (sample 0x22 10 100 false)
                        (sample 0x33 30 100 false) (sample 0x44 15 100 true)
                        (sample 0x55 25 100 false)]}
             {:track-id 2 :handler "soun" :timescale 1000 :stsd (mux/minimal-stsd "mp4a")
              :samples (mapv #(sample (+ 0xA0 %) (+ 8 %) 80 true) (range 5))}]}))

(def input {:bytes src :cid (blob/content-id src)})

;; re-encode-free pipeline: demux → trim[0,300) → mux（codec effect 不要）
(def pipe-cut
  (graph/filtergraph
   {:effects #{}
    :nodes [{:id :demux :op :demux}
            {:id :trim  :op :trim :args {:start 0 :end 300}}
            {:id :mux   :op :mux}]
    :edges [[:demux :trim] [:trim :mux]]}))

;; transcode pipeline: demux → decode → encode → mux（codec effect 必要）
(def pipe-xcode
  (graph/filtergraph
   {:effects #{:media-decode :media-encode}
    :nodes [{:id :demux  :op :demux}
            {:id :decode :op :decode :args {:codec :h264}}
            {:id :encode :op :encode :args {:codec :h265 :crf 23}}
            {:id :mux    :op :mux}]
    :edges [[:demux :decode] [:decode :encode] [:encode :mux]]}))

(defn- threw? [f kind]
  (try (f) false
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
         (= kind (:kind (ex-data e))))))

(defn run []
  ;; --- 1) BSP 決定論 + re-encode-free pipeline は deny-all でも通る（effect 宣言なし） ---
  (let [pol (policy/deny-all)
        r1  (pregel/run pipe-cut input pol)
        r2  (pregel/run pipe-cut input pol)]
    (assert (= 3 (:supersteps r1)) "3 supersteps (demux→trim→mux)")
    (assert (some? (:output-cid r1)) "produced output cid")
    (assert (= (:output-cid r1) (:output-cid r2)) "deterministic: same output cid")
    (assert (string? (:graph-cid r1)) "filtergraph-cid present")
    ;; trim 後 demux で sample 数を確認（video pts<300 → 3、audio pts<300 → 4）
    (let [d (demux/demux (:bytes (:output r1)))]
      (assert (= 3 (count (:samples (first (:tracks d))))) "cut video → 3 samples")
      (assert (= 4 (count (:samples (second (:tracks d))))) "cut audio → 4 samples")))

  ;; --- 2) capability: transcode pipeline は deny-all で拒否される ---
  (assert (threw? #(pregel/run pipe-xcode input (policy/deny-all)) :capability-denied)
          "deny-by-default rejects ungranted :media-decode")

  ;; --- 3) effect soundness(T2): node effect が graph :effects に無いと拒否 ---
  (let [bad (assoc pipe-xcode :effects #{})]   ; decode/encode を宣言から外す
    (assert (threw? #(pregel/run bad input (policy/grant (policy/deny-all)
                                                         :media-decode :media-encode))
                    :under-declaration)
            "under-declaration rejected even if granted"))

  ;; --- 4) grant すれば実行でき、per-frame gas を消費する ---
  (let [pol (-> (policy/deny-all)
                (policy/grant :media-decode)
                (policy/grant :media-encode))
        r   (pregel/run pipe-xcode input pol)]
    (assert (= 4 (:supersteps r)) "4 supersteps (demux→decode→encode→mux)")
    ;; gas = demux50 + decode(1000*10) + encode(1500*10) + mux50 = 25100
    (assert (= 25100 (:gas r)) (str "per-frame gas accounting, got " (:gas r)))
    (assert (some? (:output-cid r)) "transcode produced output"))

  ;; --- 5) fuel: gas 上限を超えると trap ---
  (let [pol (-> (policy/deny-all)
                (policy/grant :media-decode) (policy/grant :media-encode)
                (policy/with-gas-limit 5000))]   ; decode 10000 > 5000
    (assert (threw? #(pregel/run pipe-xcode input pol) :out-of-gas)
            "gas ceiling traps the run (fuel)"))

  ;; --- 6) content-addressed メモ化: 同一 input+graph の 2 回目は cache hit ---
  (let [pol   (-> (policy/deny-all) (policy/grant :media-decode) (policy/grant :media-encode))
        cache (atom {})
        a (pregel/transcode pipe-xcode input pol cache)
        b (pregel/transcode pipe-xcode input pol cache)]
    (assert (false? (:cached a)) "first run computes")
    (assert (true? (:cached b)) "second run is cache hit")
    (assert (= (:output-cid a) (:output-cid b)) "memoized output cid identical")
    (assert (= 1 (count @cache)) "single cache entry for same input+graph"))

  :ok)

(comment (run))
