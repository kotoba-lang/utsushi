(ns utsushi.backend
  "③ codec カーネルの **実装カタログと選択** —— `utsushi.codec` の façade が
  dispatch する先。

  ## なぜこれが空だったのか

  `utsushi.codec/decode` / `/encode` は第一引数に `_policy` を取りながら**使って
  いない**。docstring は「実体は capability-gated native host word」と書いており、
  その host word は存在しない。つまり façade は「どの実装が走るか」を決める場所を
  用意したまま空だった。ここがその場所である。

  ## utsushi 自身の不変条件が測定で裏付けられた（2026-07-30）

  このリポの CLAUDE.md は最初からこう書いている:

  > libavcodec を `.kotoba`/cljc で再実装しない。codec 内側ループは純 cljc では
  > framerate で回らない（**SIMD/threads 無し**・fuel・streaming 不可）

  `com-junkawasaki/root` ADR-2800002800 でこれを実測した。同一の 720x1280 YUV に
  対し、x264 を**同じスコープ・同じ全探索（`me=esa` ±8）・同じ 1 thread** に設定して:

      x264            16.2 ms/frame   → 1800 フレームで 29 秒
      純 cljc         18,140 ms/frame → 9.07 時間
                      1,120 倍

  探索範囲・候補数・thread 数を揃えてこの差なので**アルゴリズム差ではない**。内訳は
  データ表現 16.6x（実測）× SIMD 約 10x（NEON の 16 byte 幅からの推定）× その他 7x。
  4 段の kaizen（fused SAD / inter fold / half-sample 面共有 / 配列化）を通した後、
  profile は平坦（最大 15.6%）で**残りはアクセスではなく演算**——SIMD 無しには縮まない。
  そして kotoba-lang の compiler には `v128`/`i8x16`/`neon`/`avx` が src・docs・
  lang authority すべてで 0 件である。

  **したがって純 cljc codec の役割は「速度」ではない。** 可搬性（native 依存ゼロ）と、
  実 ffmpeg が復号して byte 一致する**適合性 oracle** であること。production の
  900 倍を埋めるのは platform の hardware encoder への dispatch であり、scalar
  最適化の追加増分ではない。このカタログはその dispatch を**明示的で監査可能**に
  するために存在する。

  ## 何を主張し、何を主張しないか

  各 backend は `:backend/status` を持つ。**`:executor-absent` は「使える」ではない**
  —— `select` は既定でそれを選ばない。`:webcodecs` は `org-w3-webcodecs` に**実物の
  binding がある**（`video-encoder-config-supported!` という能力プローブまである）が、
  utsushi からそれを駆動する executor は無い。そう書いてあることが重要で、
  「hardware 対応済み」と読めてはいけない。

  `:ffmpeg` は**外部プロセス**で、utsushi は「①②④ は純 cljc + EDN（外部依存ゼロ）」
  という不変条件を持つ。よってここに spawn するコードは書かない —— カタログに
  *宣言*するだけである。宣言する理由は、production が今まさに ffmpeg に依存している
  という事実を、隠れた前提から**選択の対象**に引き上げるため。"
  )

;; ---- platform -----------------------------------------------------------

(defn platform
  "現在の実行 platform を返す: `:jvm` / `:browser` / `:node`。

  ClojureScript では browser と Node を分ける必要がある —— WebCodecs は
  browser にしか無く、外部プロセス起動は browser に無い。判定は「その API が
  実在するか」で行う（user agent 文字列ではなく）。"
  []
  #?(:clj :jvm
     :cljs (if (exists? js/window) :browser :node)))

(defn webcodecs-video-encoder?
  "この platform に WebCodecs の `VideoEncoder` が実在するか。browser 以外では
  常に false。`org-w3-webcodecs/supported?` と同じ判定を、utsushi が
  org-w3-webcodecs に依存せずに行えるようにしたもの（依存を足すのは executor を
  書くときで、カタログを持つだけの今ではない）。"
  []
  #?(:clj false
     :cljs (and (exists? js/window) (exists? js/VideoEncoder))))

;; ---- catalog ------------------------------------------------------------

(def statuses
  "`:backend/status` の語彙。`:executor-absent` と `:out-of-layer` は
  **実行できない**ことを意味する —— `select` は既定で選ばない。"
  {:implemented      "utsushi から呼べば実際に走る"
   :executor-absent  "実装は存在するが utsushi から駆動する executor が無い"
   :out-of-layer     "実行主体が utsushi の外（外部プロセス等）。宣言のみ"})

(def pixel-ops
  "**実画素**の codec 処理。`utsushi.codec` の façade はこれを一切やっていない
  （`:stage` を進めてコンテナ metadata を読むだけ）——画素経路は
  `utsushi.pipeline.*` が org-iso-h264 を直接呼んでおり、façade を通らない。
  つまり docstring の言う native host word の座は、画素については今も空である。"
  #{:encode-pixels :decode-pixels})

(def facade-ops
  "`utsushi.codec/decode` / `/encode` が実際にやっていること —— opaque
  passthrough + コンテナ metadata。**任意の codec に対して成立する**（h264 だけ
  avcC 経由で実 SPS を読む）。ここを画素と混同すると、`:h265` を opaque に通す
  という既存契約を壊す（実際に壊して既存テスト 4 件を落とした）。"
  #{:encode :decode})

(def backends
  "codec op を実行しうる実装すべて。**測定値は測ったものだけ**を書く
  （`:measured/ms-per-frame` が nil = 測っていない、という意味であり
  「速い」の省略ではない）。`:backend/codecs :any` は codec 非依存。"
  [{:backend/id :opaque
    :backend/kind :passthrough
    :backend/status :implemented
    :backend/ops facade-ops
    :backend/codecs :any
    :backend/platforms #{:jvm :browser :node}
    :backend/provider "utsushi.codec (+ org-iso-isobmff / org-iso-h264 の parameter set 層)"
    :backend/bit-exact-oracle? false
    :measured/ms-per-frame nil
    :backend/note
    (str "R0/R1 の実体。bytes は opaque に通し、コンテナ metadata だけ読む"
         "（h264 は avcC 経由で実 SPS/PPS）。画素は触らないので codec 非依存で成立し、"
         ":h265 のような未対応 codec も通る —— これが既存の契約である。")}

   {:backend/id :cljc
    :backend/kind :portable
    :backend/status :implemented
    :backend/ops pixel-ops
    :backend/codecs #{:h264 :aac}
    :backend/platforms #{:jvm :browser :node}
    :backend/provider "org-iso-h264 / org-iso-aac"
    :backend/bit-exact-oracle? true
    :measured/ms-per-frame 18140
    :measured/at "720x1280 P frame, 1 thread, org-iso-h264 30f36c0, 2026-07-30"
    :backend/note
    (str "実 ffmpeg が復号でき byte 一致する参照実装。速度は x264 の約 1/1120 で、"
         "profile が平坦になった後は SIMD 無しには縮まない（ADR-2800002800）。"
         "hardware 経路の出力を検証する oracle としての価値が本体。")}

   {:backend/id :webcodecs
    :backend/kind :hardware
    :backend/status :executor-absent
    :backend/ops pixel-ops
    :backend/codecs #{:h264 :aac :vp8 :vp9 :av1}
    :backend/platforms #{:browser}
    :backend/provider "org-w3-webcodecs (w3.webcodecs)"
    :backend/bit-exact-oracle? false
    :measured/ms-per-frame nil
    :backend/note
    (str "binding は実在する（make-video-encoder / configure-video-encoder! / "
         "encode-video-frame! / video-encoder-flush!、能力プローブ "
         "video-encoder-config-supported! まで）。無いのは utsushi 側の executor —— "
         "filtergraph の :encode node を VideoEncoder の呼び出し列に落とす部分。"
         "出力は hardware 依存なので bit-exact な期待値を持てない（品質検証は "
         ":cljc oracle で復号して比較する形になる）。")}

   {:backend/id :ffmpeg
    :backend/kind :external-process
    :backend/status :out-of-layer
    :backend/ops (into pixel-ops #{:demux :mux})
    :backend/codecs #{:h264 :h265 :aac :vp9 :av1}
    :backend/platforms #{:jvm :node}
    :backend/provider "libavcodec (外部プロセス)"
    :backend/bit-exact-oracle? false
    :measured/ms-per-frame 16
    :measured/at "720x1280, x264 me=esa merange=8 subme=7, 1 thread, 2026-07-30"
    :backend/note
    (str "production が現に使っている経路。utsushi は「①②④ は純 cljc + EDN、"
         "外部依存ゼロ」なのでここに spawn は書かない —— 宣言だけ置いて、"
         "production の ffmpeg 依存を隠れた前提から選択対象に引き上げる。"
         "16 ms/frame は :cljc と同一入力・同一スコープ・同一 1 thread の実測。")}])

(defn by-id [id] (first (filter #(= id (:backend/id %)) backends)))

(def executable-statuses
  "実際に走る status。`select` の既定フィルタ。"
  #{:implemented})

;; ---- selection ----------------------------------------------------------

(defn rejections
  "各 backend を `op`/`codec`/`plat` に対して評価し、選べない理由を並べて返す
  （`[{:backend/id .. :reason ..}]`、選べるものは reason nil）。

  理由を捨てずに返すのが要点 —— 「backend が無い」だけのエラーは、
  platform 違いなのか status なのか codec 非対応なのかを呼び手に伝えない。"
  [op codec plat {:keys [allow-statuses providers] :or {allow-statuses executable-statuses}}]
  (mapv (fn [{:keys [backend/id backend/ops backend/codecs backend/platforms backend/status]
              :as b}]
          (let [prov (let [p (get providers id)]
                       (when (and p (contains? (:provider/ops p) op)) p))]
            {:backend/id id
             :backend/status status
             :backend/provided? (some? prov)
             :reason (cond
                       (not (contains? ops op)) (str "op " op " を扱わない")
                       (and codec (not= :any codecs) (not (contains? codecs codec)))
                       (str "codec " codec " 非対応")
                       (not (contains? platforms plat)) (str "platform " plat " で動かない")
                       ;; provider が注入されていれば status を待たずに実行可能。
                       ;; status は「utsushi 単体では」の話で、provider の有無は
                       ;; その deployment の話だから。
                       (and (not prov) (not (contains? allow-statuses status)))
                       (str "status " status " —— " (get statuses status "不明")
                            "（provider を注入すれば実行可能）")
                       :else nil)
             ;; カタログの :backend/provider は「誰の実装か」を書いた説明文字列
             ;; なので、注入された executor は別のキーに置く。同じキーに入れると
             ;; 「libavcodec (外部プロセス)」という文字列を関数として呼ぶことになる。
             :backend (if prov (assoc b :backend/executor prov) b)}))
        backends))

(defn available
  "`op`（`:encode`/`:decode`）と `codec` を、`plat`（既定は現在の platform）で
  実行できる backend を、カタログ順に返す。"
  ([op codec] (available op codec (platform) {}))
  ([op codec plat] (available op codec plat {}))
  ([op codec plat opts]
   (mapv :backend (filter (comp nil? :reason) (rejections op codec plat opts)))))

(defn- opts-of
  "policy から rejections/select 用の opts を作る。`:backend/providers` を
  拾い忘れると、注入した provider が選択に反映されない。"
  [policy]
  (-> (select-keys policy [:allow-statuses])
      (assoc :providers (:backend/providers policy))))

(defn select
  "`op`/`codec` を実行する backend を 1 つ選ぶ。`policy` の
  `:backend/prefer`（backend id の並び）が優先順を決め、指定が無ければカタログ順。

  実行できる backend が無ければ **ex-info を投げる**——却下理由を全部添えて。
  filtergraph を組んだ時点で失敗させるのが目的で、実行時に「VideoEncoder が
  undefined」を踏むより早く、原因も具体的である。"
  ([op codec] (select op codec {}))
  ([op codec policy] (select op codec policy (platform)))
  ([op codec policy plat]
   (let [opts (opts-of policy)
         all (rejections op codec plat opts)
         ok (filter (comp nil? :reason) all)
         prefer (vec (:backend/prefer policy))
         ;; 順位表は map で引く。`.indexOf` は JVM 専用 interop で、この ns は
         ;; cljs でも動かなければならない（browser が :webcodecs の platform）。
         ;; 指定外は prefer の後ろに回し、その中の順序は sort-by が stable なので
         ;; カタログ順が保たれる。
         rank (into {} (map-indexed (fn [i id] [id i]) prefer))
         ranked (if (seq prefer)
                  (sort-by (fn [{:keys [backend/id]}] (get rank id (count prefer))) ok)
                  ok)]
     (if-let [chosen (first ranked)]
       (:backend chosen)
       (throw (ex-info (str "実行できる codec backend が無い: op " op
                            " codec " codec " platform " plat)
                       {:kind :no-backend :op op :codec codec :platform plat
                        :rejections (mapv #(select-keys % [:backend/id :backend/status :reason])
                                          all)}))))))

;; ---- provider（executor の注入） ----------------------------------------
;;
;; カタログは「何が在りうるか」しか言えない。**実際に走るかは deployment の
;; 性質**であって、library に焼ける事実ではない —— browser には WebCodecs が
;; あり JVM には無い、ffmpeg は在る環境と無い環境がある。したがって executor は
;; 呼び手が **provider として注入**し、utsushi は契約の検証と grant 強制だけを持つ。
;;
;; この形は発明ではなく `kototama.component-provider` の写しである。あちらは
;; 「providers のキーは granted WIT imports と厳密一致しなければならない」
;; 「capability 名だけでは authority ではない」「provider は invocation adapter を
;; 露出しなければならない」を prepare! で強制する。同じ 3 点をここでも強制する。
;;
;; これで utsushi の不変条件（①②④ は純 cljc + EDN、外部依存ゼロ）は保たれる ——
;; ffmpeg を spawn するコードも WebCodecs を叩くコードも utsushi には入らず、
;; **契約だけが入る**。

(defn provider
  "backend `id` の executor を作る。`invoke` は `(fn [op args] result)`。

  `ops` は**その provider が実際に提供する op**で、カタログがその backend に
  宣言している op の**部分集合でなければならない** —— 宣言していない op を
  provider が名乗れるなら、カタログは監査の役に立たない
  （`kototama.component-provider`: 「capability 名だけでは authority ではない」）。"
  [id ops invoke]
  (let [b (by-id id)]
    (when-not b
      (throw (ex-info (str "未知の backend: " id)
                      {:kind :unknown-backend :backend/id id
                       :known (mapv :backend/id backends)})))
    (when-not (ifn? invoke)
      (throw (ex-info "provider は invocation adapter を露出しなければならない"
                      {:kind :provider-not-invocable :backend/id id})))
    (let [declared (:backend/ops b)
          claimed (set ops)]
      (when-not (seq claimed)
        (throw (ex-info "provider は少なくとも 1 つの op を提供しなければならない"
                        {:kind :provider-no-ops :backend/id id})))
      (when-not (every? declared claimed)
        (throw (ex-info (str "provider が宣言外の op を名乗っている: " id)
                        {:kind :provider-op-not-declared :backend/id id
                         :declared declared :claimed claimed
                         :undeclared (into #{} (remove declared) claimed)})))
      {:provider/backend id :provider/ops claimed :provider/invoke invoke})))

(defn with-provider
  "policy に provider を登録する。登録された backend は、その provider が提供する
  op に限り **実行可能として扱われる** —— status が `:executor-absent` /
  `:out-of-layer` でも。

  status を書き換えるのではなく policy 側に持つのが要点である。カタログの
  `:webcodecs` は「utsushi 単体では executor が無い」という**恒久的に正しい事実**で、
  provider の有無は**その deployment の**事実だから、別の場所に住むべきである。"
  [policy prov]
  (update policy :backend/providers assoc (:provider/backend prov) prov))

(defn provider-for
  "policy に登録された、`id` が `op` を提供する provider（無ければ nil）。"
  [policy id op]
  (let [p (get-in policy [:backend/providers id])]
    (when (and p (contains? (:provider/ops p) op)) p)))

(defn provided?
  "`id` は `op` について、この policy の下で実行可能か。"
  [policy id op]
  (some? (provider-for policy id op)))

(defn invoke!
  "選ばれた backend の provider を通して `op` を実行する。provider が無ければ
  ex-info —— **カタログに載っていることと実行できることは別**であり、その区別を
  実行時に潰さない。"
  [backend op args]
  (if-let [prov (:backend/executor backend)]
    ((:provider/invoke prov) op args)
    (throw (ex-info (str "backend " (:backend/id backend) " に provider が無い（op " op "）")
                    {:kind :no-provider :backend/id (:backend/id backend) :op op
                     :status (:backend/status backend)}))))

;; ---- gas ----------------------------------------------------------------

(defn gas-scale
  "backend の per-frame 実測コストを、`utsushi.policy/gas-cost` の基準
  （`:cljc` = 1）に対する倍率として返す。測っていない backend は 1（= 楽観も
  悲観もしない）。

  同じ `:encode` op のコストが backend で 3 桁違うので、gas を op だけで決めると
  見積りが嘘になる —— 実測は :cljc 18,140 ms/frame に対し :ffmpeg 16 ms/frame。"
  [backend]
  (let [base (:measured/ms-per-frame (by-id :cljc))
        mine (:measured/ms-per-frame backend)]
    (if (and base mine (pos? base) (pos? mine))
      (/ (double mine) (double base))
      1.0)))
