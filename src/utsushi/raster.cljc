(ns utsushi.raster
  "④ filtergraph の画素演算(pure)。scale / pad / fit / hold / zoompan。

  なぜここか: これらは ffmpeg の `-vf` で言えば `scale`・`pad`・
  `tpad=stop_mode=clone`・`zoompan` に相当し、**entropy coding を一切含まない
  ただの画素演算**である。codec カーネル(層③)でもコンテナ(層①)でもなく、
  filtergraph(層④)の中身そのものなので utsushi に属する。

  平面の表現は `org-iso-h264` の `h264.encode`/`h264.decode` が既に使っている
  ものに合わせる ——「フラット row-major の整数ベクトル + `:width`/`:height`」。
  新しいフレーム型を作らない理由は単純で、filter の出力をそのまま encoder の
  入力に渡せることが目的だから。YUV 4:2:0 なら luma に 1 回、chroma には
  半分の寸法で 2 回適用する(`plane-*` を直接呼ぶ)。

  すべて純関数。IO も乱数も時計も無い。

  **ffmpeg との一致度(実測 2026-07-30)**: `plane-scale` を実フレーム
  (720x1280 luma)で 360x640 に落とし、`ffmpeg -vf scale=...:flags=bilinear`
  と比較すると **平均絶対差 1.001 / 最大 32 / 1 以内 83.8%**。
  **bit-identical ではない** —— swscale の \"bilinear\" は教科書的な双線形では
  なく、固定小数点のタップ生成と彩度考慮が入る。生成映像を素材にする用途では
  この差は視認できないが、「ffmpeg と同一」と書いてはいけない。速度は同条件で
  67 ms(ffmpeg は ~5 ms、約 13 倍遅い)。

  一致が必要な場面(既存出力の再現)では ffmpeg を使うこと。この ns の用途は
  native 依存を持たない経路を用意することであって、ffmpeg の置換ではない。"
  (:require [clojure.string :as str]))

;; JVM interop は .cljc に置けない(org-iso-h264 の `round-nearest` が同じ理由で
;; 避けている)。floor / round を移植可能に定義する。
(defn- floor* [x]
  (let [x (double x) f (- x (mod x 1))]
    #?(:clj (long f) :cljs f)))

(defn- round* [x]
  (floor* (+ (double x) 0.5)))

;; ── 平面アクセス ─────────────────────────────────────────────────────────────

(defn plane
  "フラット row-major ベクトル + 寸法 -> 平面マップ。"
  [px width height]
  {:width (long width) :height (long height) :px (vec px)})

(defn at
  "平面の (x,y) 画素。範囲外は最近傍の縁にクランプする —— 縁の外側を 0 と
  みなすと scale/zoompan が枠の内側に暗い線を作る(ffmpeg も端は複製する)。"
  [{:keys [width height px]} x y]
  (let [x (max 0 (min (dec (long width)) (long x)))
        y (max 0 (min (dec (long height)) (long y)))]
    (long (nth px (+ (* y (long width)) x)))))

(defn- clip8 [v] (max 0 (min 255 (long v))))

;; ── scale (bilinear) ────────────────────────────────────────────────────────

(defn plane-scale
  "平面を `dw`x`dh` へ双線形補間で再標本化する。

  座標は画素中心で写す((i+0.5) のスケール後に -0.5)。これを省くと 0.5 画素
  ずれて、拡大時に上端/左端が引き伸びる。"
  [{:keys [width height] :as p} dw dh]
  (let [dw (long dw) dh (long dh)
        sw (long width) sh (long height)
        fx (/ (double sw) dw)
        fy (/ (double sh) dh)]
    (plane
     (persistent!
      (reduce
       (fn [acc dy]
         (let [sy (- (* (+ dy 0.5) fy) 0.5)
               y0 (floor* sy)
               wy (- sy y0)]
           (reduce
            (fn [a dx]
              (let [sx (- (* (+ dx 0.5) fx) 0.5)
                    x0 (floor* sx)
                    wx (- sx x0)
                    p00 (at p x0 y0)       p10 (at p (inc x0) y0)
                    p01 (at p x0 (inc y0)) p11 (at p (inc x0) (inc y0))
                    top (+ p00 (* wx (- p10 p00)))
                    bot (+ p01 (* wx (- p11 p01)))]
                (conj! a (clip8 (round* (+ top (* wy (- bot top))))))))
            acc
            (range dw))))
       (transient [])
       (range dh)))
     dw dh)))

;; ── pad / fit ───────────────────────────────────────────────────────────────

(defn fit-inside
  "`sw`x`sh` を `dw`x`dh` に**アスペクト比を保って収める**寸法。
  ffmpeg の `force_original_aspect_ratio=decrease` と同じ意味。
  偶数に丸める —— 4:2:0 の chroma が半分の寸法を取るので奇数は割れない。"
  [sw sh dw dh]
  (let [s (min (/ (double dw) sw) (/ (double dh) sh))
        w (max 2 (* 2 (round* (/ (* s sw) 2.0))))
        h (max 2 (* 2 (round* (/ (* s sh) 2.0))))]
    [(min w (long dw)) (min h (long dh))]))

(defn plane-pad
  "平面を `dw`x`dh` のキャンバス中央に置く。余白は `fill`(既定 0)。
  ffmpeg の `pad=dw:dh:(ow-iw)/2:(oh-ih)/2` と同じ配置。"
  [{:keys [width height] :as p} dw dh & [fill]]
  (let [dw (long dw) dh (long dh)
        fill (long (or fill 0))
        x0 (quot (- dw (long width)) 2)
        y0 (quot (- dh (long height)) 2)]
    (plane
     (persistent!
      (reduce
       (fn [acc y]
         (reduce
          (fn [a x]
            (conj! a (if (and (>= x x0) (< x (+ x0 (long width)))
                              (>= y y0) (< y (+ y0 (long height))))
                       (at p (- x x0) (- y y0))
                       fill)))
          acc
          (range dw)))
       (transient [])
       (range dh)))
     dw dh)))

(defn plane-fit
  "scale + pad の組。`douga.ffmpeg/scene-segment-cmd` が組んでいる
  `scale=W:H:force_original_aspect_ratio=decrease,pad=W:H:(ow-iw)/2:(oh-ih)/2`
  と同じ結果を、ffmpeg を呼ばずに得る。"
  [{:keys [width height] :as p} dw dh & [fill]]
  (let [[iw ih] (fit-inside width height dw dh)]
    (-> p (plane-scale iw ih) (plane-pad dw dh (or fill 0)))))

;; ── hold (tpad=stop_mode=clone) ─────────────────────────────────────────────

(defn hold
  "フレーム列を `n` 枚まで**最後のフレームを複製して**伸ばす。既に `n` 枚
  以上あれば先頭 `n` 枚に切る。

  ffmpeg の `tpad=stop_mode=clone` + `-t` と同じ意味で、生成クリップが計画尺
  より短い時に黒味を残さないためのもの(短いクリップは保持、長いクリップは
  切り詰め)。空列は空のまま返す —— 無いフレームは複製できない。"
  [frames n]
  (let [frames (vec frames) have (count frames) n (long n)]
    (cond
      (zero? have) frames
      (>= have n) (subvec frames 0 n)
      :else (into frames (repeat (- n have) (peek frames))))))

;; ── zoompan (2.5D camera motion) ────────────────────────────────────────────

(def motions
  "`dougaka.pipeline/motion-segment-cmd` が使っている 4 つと同じ語彙。"
  #{:push-in :pull-back :pan-left :pan-right})

(defn motion-window
  "モーション種別 + 進捗 `t`(0..1) + 平面寸法 + 最大ズーム -> 切り出す窓
  `[x y w h]`(実数)。

  `:push-in` は 1.0 -> zoom へ寄り、`:pull-back` はその逆。パンはズームを
  固定して窓を横に送る。ffmpeg の zoompan 式と同じ意味を、式文字列ではなく
  データで表す。"
  [motion t width height & [zoom]]
  (let [t (max 0.0 (min 1.0 (double t)))
        zmax (double (or zoom 1.12))
        w (double width) h (double height)
        z (case motion
            :push-in (+ 1.0 (* t (- zmax 1.0)))
            :pull-back (- zmax (* t (- zmax 1.0)))
            zmax)
        vw (/ w z) vh (/ h z)
        x (case motion
            :pan-left (* (- w vw) (- 1.0 t))
            :pan-right (* (- w vw) t)
            (/ (- w vw) 2.0))
        y (/ (- h vh) 2.0)]
    [x y vw vh]))

(defn plane-crop-scale
  "実数窓 `[x y w h]` を切り出して `dw`x`dh` に双線形で伸ばす。zoompan の
  1 フレーム分。"
  [p [x y w h] dw dh]
  (let [dw (long dw) dh (long dh)
        fx (/ (double w) dw) fy (/ (double h) dh)]
    (plane
     (persistent!
      (reduce
       (fn [acc dy]
         (let [sy (+ (double y) (- (* (+ dy 0.5) fy) 0.5))
               y0 (floor* sy) wy (- sy y0)]
           (reduce
            (fn [a dx]
              (let [sx (+ (double x) (- (* (+ dx 0.5) fx) 0.5))
                    x0 (floor* sx) wx (- sx x0)
                    p00 (at p x0 y0)       p10 (at p (inc x0) y0)
                    p01 (at p x0 (inc y0)) p11 (at p (inc x0) (inc y0))
                    top (+ p00 (* wx (- p10 p00)))
                    bot (+ p01 (* wx (- p11 p01)))]
                (conj! a (clip8 (round* (+ top (* wy (- bot top))))))))
            acc
            (range dw))))
       (transient [])
       (range dh)))
     dw dh)))

(defn zoompan
  "静止平面 -> `n` フレームの 2.5D カメラモーション列。
  `dougaka.pipeline` が ffmpeg zoompan で作っていたものと同じ意図。"
  [p motion n dw dh & [zoom]]
  (let [n (long n)]
    (if (< n 1)
      []
      (mapv (fn [i]
              (let [t (if (= n 1) 0.0 (/ (double i) (dec n)))]
                (plane-crop-scale p (motion-window motion t (:width p) (:height p) zoom)
                                  dw dh)))
            (range n)))))

;; ── filtergraph の op 語彙 ──────────────────────────────────────────────────

(def ops
  "この ns が実装する filtergraph op。`utsushi.policy` がこれを見て
  effect(なし = 純粋)と gas を決める。"
  {:scale    {:pure? true :args #{:width :height}}
   :pad      {:pure? true :args #{:width :height :fill}}
   :fit      {:pure? true :args #{:width :height :fill}}
   :hold     {:pure? true :args #{:frames}}
   :zoompan  {:pure? true :args #{:motion :frames :width :height :zoom}}})

(defn describe
  "op + args -> 一行の説明。graph をそのまま人に見せるため。"
  [op args]
  (str (name op) "("
       (str/join " " (for [[k v] (sort-by key args)] (str (name k) "=" v)))
       ")"))
