(ns utsushi.backend-test
  "`utsushi.backend` のテスト。

  ここで固定したいのは速度ではなく **主張の正しさ** である。カタログは
  「hardware 対応済み」と読まれてはいけないし、`:ffmpeg` が宣言だけであることも
  消えてはいけない。したがってテストは status の意味・platform 制約・却下理由が
  伝わることを検査する（`com-junkawasaki/root` ADR-2800002800）。"
  (:require [clojure.test :refer [deftest is testing]]
            [utsushi.backend :as backend]
            [utsushi.policy :as policy]
            [utsushi.graph :as graph]))

(deftest catalog-claims-only-what-is-true
  (testing "実行可能 status を持つのは :opaque（façade）と :cljc（画素）だけ —— カタログが「hardware 対応済み」と読めてはいけない"
    (is (= #{:opaque :cljc}
           (into #{} (map :backend/id)
                 (filter #(contains? backend/executable-statuses (:backend/status %))
                         backend/backends)))))
  (testing ":webcodecs は binding はあるが executor が無い、と明示している"
    (let [wc (backend/by-id :webcodecs)]
      (is (= :executor-absent (:backend/status wc)))
      (is (= #{:browser} (:backend/platforms wc)))
      (is (= "org-w3-webcodecs (w3.webcodecs)" (:backend/provider wc)))
      (is (false? (:backend/bit-exact-oracle? wc))
          "hardware 出力に bit-exact な期待値は持てない")))
  (testing ":ffmpeg は utsushi の外にあると明示している（純 cljc + 外部依存ゼロの不変条件）"
    (is (= :out-of-layer (:backend/status (backend/by-id :ffmpeg)))))
  (testing "測っていないコストは nil で、0 や推測値で埋めない"
    (is (nil? (:measured/ms-per-frame (backend/by-id :webcodecs))))
    (is (every? #(or (nil? (:measured/ms-per-frame %)) (some? (:measured/at %)))
                backend/backends)
        "実測値には測定条件が付いていること"))
  (testing "oracle を主張するのは :cljc だけ"
    (is (= #{:cljc}
           (into #{} (map :backend/id) (filter :backend/bit-exact-oracle? backend/backends))))))

(deftest selection-respects-platform-and-status
  (testing "façade op は :opaque が受ける —— 任意 codec の opaque passthrough が既存契約"
    (is (= :opaque (:backend/id (backend/select :encode :h264 {} :jvm))))
    (is (= :opaque (:backend/id (backend/select :decode :h265 {} :jvm)))
        ":h265 も通る（画素を触らないので codec 非依存）"))
  (testing "画素 op では JVM で :cljc（:ffmpeg は out-of-layer、:webcodecs は browser 専用）"
    (is (= :cljc (:backend/id (backend/select :encode-pixels :h264 {} :jvm)))))
  (testing "browser でも :cljc —— :webcodecs は executor が無いので既定では選ばれない"
    (is (= :cljc (:backend/id (backend/select :encode-pixels :h264 {} :browser)))))
  (testing "prefer で :webcodecs を先に置いても、status が executable でなければ選ばれない"
    (is (= :cljc (:backend/id (backend/select :encode-pixels :h264
                                              {:backend/prefer [:webcodecs :cljc]}
                                              :browser)))))
  (testing "status を明示的に許可したときだけ :webcodecs が選ばれる —— つまり「使える」と言うには呼び手の明示が必要"
    (is (= :webcodecs
           (:backend/id (backend/select :encode-pixels :h264
                                        {:backend/prefer [:webcodecs]
                                         :allow-statuses #{:implemented :executor-absent}}
                                        :browser)))))
  (testing "prefer は順序を効かせる"
    (is (= :ffmpeg
           (:backend/id (backend/select :encode-pixels :h264
                                        {:backend/prefer [:ffmpeg :cljc]
                                         :allow-statuses #{:implemented :out-of-layer}}
                                        :jvm))))))

(deftest refusal-carries-the-reason
  (testing "実行できる backend が無ければ投げ、却下理由を全部添える"
    (let [e (try (backend/select :encode-pixels :h265 {} :jvm)
                 (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e e))
          d (ex-data e)]
      (is (= :no-backend (:kind d)))
      (is (= :h265 (:codec d)))
      (testing "全 backend について理由が付く —— 「backend が無い」だけでは platform 違いか status か codec 非対応か分からない"
        (is (= (count backend/backends) (count (:rejections d))))
        (is (every? :reason (:rejections d))))
      (testing "h265 を扱えない :cljc は codec 非対応として却下される"
        (is (re-find #"codec" (:reason (first (filter #(= :cljc (:backend/id %))
                                                      (:rejections d)))))))))
  (testing "browser で外部プロセスを要求する codec は platform 理由で却下される"
    (let [d (ex-data (try (backend/select :encode-pixels :h265
                                         {:allow-statuses #{:implemented :out-of-layer}}
                                         :browser)
                          (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e e)))]
      (is (re-find #"platform" (:reason (first (filter #(= :ffmpeg (:backend/id %))
                                                       (:rejections d)))))))))

(deftest available-lists-only-runnable
  (is (= [:opaque] (mapv :backend/id (backend/available :encode :h264 :jvm)))
      "façade op を受けるのは :opaque だけ")
  (is (= [:cljc] (mapv :backend/id (backend/available :encode-pixels :h264 :jvm))))
  (is (= [] (mapv :backend/id (backend/available :encode-pixels :vp9 :jvm)))
      ":cljc は vp9 を扱わない")
  (is (= [:webcodecs] (mapv :backend/id
                            (backend/available :encode-pixels :vp9 :browser
                                               {:allow-statuses #{:executor-absent}})))))

(deftest gas-scale-reflects-the-measured-three-orders-of-magnitude
  (testing ":cljc は基準なので 1"
    (is (== 1.0 (backend/gas-scale (backend/by-id :cljc)))))
  (testing ":ffmpeg の実測は :cljc の 1/1000 未満 —— gas を op だけで決めると 3 桁外れる"
    (let [s (backend/gas-scale (backend/by-id :ffmpeg))]
      (is (< s 0.002))
      (is (pos? s))))
  (testing "測っていない backend は 1（楽観も悲観もしない）"
    (is (== 1.0 (backend/gas-scale (backend/by-id :webcodecs))))))

;; ---- graph 検査への配線 ------------------------------------------------

(def ^:private transcode-graph
  (graph/filtergraph
   {:effects #{:media-decode :media-encode}
    :nodes [{:id :demux :op :demux}
            {:id :dec :op :decode :args {:codec :h264}}
            {:id :enc :op :encode :args {:codec :h264}}
            {:id :mux :op :mux}]
    :edges [[:demux :dec] [:dec :enc] [:enc :mux]]}))

(def ^:private granted
  (-> (policy/deny-all)
      (policy/grant :media-decode)
      (policy/grant :media-encode)))

(deftest check-graph-now-rejects-unrunnable-codecs-at-build-time
  (testing "実行できる backend がある codec なら通る"
    (is (map? (policy/check-graph transcode-graph (assoc granted :platform :jvm)))))
  (testing ":h265 は既定では通る —— façade の opaque passthrough が既存契約であり、既定を画素にすると壊れる"
    (let [g (graph/filtergraph
             {:effects #{:media-encode}
              :nodes [{:id :enc :op :encode :args {:codec :h265}}]
              :edges []})]
      (is (map? (policy/check-graph g (assoc granted :platform :jvm))))
      (testing "画素処理だと宣言した graph だけ、backend の無い codec で落ちる"
        (let [d (ex-data (try (policy/check-graph g (assoc granted :platform :jvm
                                                          :require-pixel-backend? true))
                              (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e e)))]
          (is (= :no-backend (:kind d)))
          (is (= :encode-pixels (:op d)))
          (is (seq (:rejections d)))))))
  (testing "effect/capability の既存検査は変わらない —— backend 検査はその後段"
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs :default)
                 (policy/check-graph transcode-graph (policy/deny-all))))))

(deftest graph-gas-is-backend-aware
  (let [frames 1800
        pixel (fn [extra] (merge granted {:platform :jvm :gas-limit nil
                                          :require-pixel-backend? true} extra))
        cljc-gas (policy/graph-gas transcode-graph (pixel {}) frames)
        ff-gas (policy/graph-gas transcode-graph
                                 (pixel {:backend/prefer [:ffmpeg]
                                         :allow-statuses #{:implemented :out-of-layer}})
                                 frames)]
    (testing "同じ graph・同じフレーム数でも、走る backend で見積りが 3 桁変わる"
      (is (> cljc-gas (* 100 ff-gas))
          (str "cljc " cljc-gas " vs ffmpeg " ff-gas)))
    (testing "per-frame op はフレーム数で伸びる —— ここを漏らすと 1800 フレームが 1 フレーム分の見積りで通る"
      (is (> cljc-gas (policy/graph-gas transcode-graph (pixel {}) 1))))
    (testing "上限を超える見積りは組んだ時点で拒否される（実行して fuel を尽かすのではなく）"
      (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs :default)
                   (policy/graph-gas transcode-graph
                                     (pixel {:gas-limit 1000})
                                     frames))))))

(deftest platform-detection-is-by-capability-not-by-name
  (testing "JVM では browser 判定が立たない"
    #?(:clj (do (is (= :jvm (backend/platform)))
                (is (false? (backend/webcodecs-video-encoder?))))
       :cljs (is (contains? #{:browser :node} (backend/platform))))))

;; ---- provider（executor の注入） ---------------------------------------
;;
;; カタログは「何が在りうるか」しか言えない。実際に走るかは deployment の性質で
;; あって library に焼ける事実ではない。この節は、その区別が保たれること —— 注入
;; されるまで実行できず、注入されれば status を待たずに実行でき、宣言外の op を
;; 名乗る provider は拒まれること —— を検査する。形は
;; `kototama.component-provider/prepare!` の写しである。

(defn- recording-provider
  "呼ばれた op/args を記録するだけの provider（本物の ffmpeg/WebCodecs の代わり）。"
  [id ops log]
  (backend/provider id ops (fn [op args] (swap! log conj [op args]) {:ok id})))

(deftest provider-must-match-what-the-catalog-declares
  (testing "宣言外の op を名乗る provider は拒まれる —— capability 名だけでは authority ではない"
    (let [d (ex-data (try (backend/provider :webcodecs #{:encode-pixels :demux} (fn [_ _] nil))
                          (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e e)))]
      (is (= :provider-op-not-declared (:kind d)))
      (is (= #{:demux} (:undeclared d)) ":webcodecs は demux を宣言していない")))
  (testing "invocation adapter が無い provider は拒まれる"
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs :default)
                 (backend/provider :webcodecs #{:encode-pixels} "not-a-fn"))))
  (testing "op を 1 つも提供しない provider は拒まれる"
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs :default)
                 (backend/provider :webcodecs #{} (fn [_ _] nil)))))
  (testing "未知の backend は拒まれる"
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs :default)
                 (backend/provider :videotoolbox #{:encode-pixels} (fn [_ _] nil)))))
  (testing "宣言の部分集合なら通る"
    (let [p (backend/provider :ffmpeg #{:encode-pixels} (fn [_ _] :ok))]
      (is (= :ffmpeg (:provider/backend p)))
      (is (= #{:encode-pixels} (:provider/ops p))))))

(deftest injecting-a-provider-makes-a-backend-runnable
  (let [log (atom [])
        pol (backend/with-provider {} (recording-provider :ffmpeg #{:encode-pixels} log))]
    (testing "注入前は :cljc が選ばれる（:ffmpeg は out-of-layer）"
      (is (= :cljc (:backend/id (backend/select :encode-pixels :h264 {} :jvm)))))
    (testing "注入後は prefer で :ffmpeg を選べる —— status を書き換えずに"
      (let [b (backend/select :encode-pixels :h264
                              (assoc pol :backend/prefer [:ffmpeg]) :jvm)]
        (is (= :ffmpeg (:backend/id b)))
        (is (= :out-of-layer (:backend/status b))
            "カタログの status は「utsushi 単体では」の恒久的事実なので変わらない")
        (is (some? (:backend/executor b)))))
    (testing "provider が提供していない op には効かない"
      (is (= :cljc (:backend/id (backend/select :decode-pixels :h264
                                                (assoc pol :backend/prefer [:ffmpeg]) :jvm)))))
    (testing "platform 制約は provider では超えられない —— browser で外部プロセスは動かない"
      (is (= :cljc (:backend/id (backend/select :encode-pixels :h264
                                                (assoc pol :backend/prefer [:ffmpeg]) :browser)))))
    (testing "invoke! は provider を通す"
      (let [b (backend/select :encode-pixels :h264 (assoc pol :backend/prefer [:ffmpeg]) :jvm)]
        (is (= {:ok :ffmpeg} (backend/invoke! b :encode-pixels {:frames 3})))
        (is (= [[:encode-pixels {:frames 3}]] @log))))
    (testing "provider の無い backend を invoke! すると、カタログに在ることと実行できることの区別が保たれる"
      (let [d (ex-data (try (backend/invoke! (backend/by-id :webcodecs) :encode-pixels {})
                            (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e e)))]
        (is (= :no-provider (:kind d)))
        (is (= :executor-absent (:status d)))))))

(deftest provided-backends-satisfy-the-graph-check
  (testing "provider があれば、その codec の画素 graph が組めるようになる"
    (let [g (graph/filtergraph
             {:effects #{:media-encode}
              :nodes [{:id :enc :op :encode :args {:codec :h265}}]
              :edges []})
          base (assoc granted :platform :jvm :require-pixel-backend? true)]
      (testing "provider 無しでは :h265 の画素 backend が無く落ちる"
        (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs :default)
                     (policy/check-graph g base))))
      (testing "ffmpeg provider を注入すると同じ graph が通る"
        (is (map? (policy/check-graph
                   g (-> base
                         (backend/with-provider
                           (backend/provider :ffmpeg #{:encode-pixels} (fn [_ _] :ok)))
                         (assoc :backend/prefer [:ffmpeg])))))))))
