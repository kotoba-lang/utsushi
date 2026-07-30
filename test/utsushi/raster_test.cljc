(ns utsushi.raster-test
  (:require [clojure.test :refer [deftest is testing]]
            [utsushi.raster :as r]))

(defn- ramp
  "w*h の平面。値は x+y*w を 255 で飽和 —— 補間のずれが値のずれとして出る。"
  [w h]
  (r/plane (vec (for [y (range h) x (range w)] (min 255 (+ x (* y w))))) w h))

(deftest edge-clamp-not-zero
  ;; 縁の外を 0 とみなすと scale/zoompan が枠の内側に暗い線を作る。
  (let [p (r/plane [10 20 30 40] 2 2)]
    (is (= 10 (r/at p -5 -5)))
    (is (= 40 (r/at p 99 99)))
    (is (= 20 (r/at p 1 0)))))

(deftest scale-identity-is-exact
  (testing "同寸への scale は画素を変えない —— ここがずれると全部ずれる"
    (let [p (ramp 8 6)]
      (is (= (:px p) (:px (r/plane-scale p 8 6)))))))

(deftest scale-of-a-flat-plane-stays-flat
  ;; 双線形は凸結合なので、一定値の平面は拡大縮小しても一定値のまま。
  ;; 中心合わせを間違えると端で値が崩れるので、これが一番効く不変条件。
  (doseq [[dw dh] [[3 3] [16 9] [1 1] [31 17]]]
    (let [p (r/plane (vec (repeat (* 4 4) 128)) 4 4)
          out (r/plane-scale p dw dh)]
      (is (= (* dw dh) (count (:px out))))
      (is (every? #(= 128 %) (:px out))
          (str "flat plane must stay flat at " dw "x" dh)))))

(deftest scale-halves-and-doubles-sanely
  (let [p (ramp 4 4)
        down (r/plane-scale p 2 2)
        up (r/plane-scale p 8 8)]
    (is (= [2 2] [(:width down) (:height down)]))
    (is (= [8 8] [(:width up) (:height up)]))
    (testing "縮小は単調性を保つ(ramp なので左上 < 右下)"
      (is (< (r/at down 0 0) (r/at down 1 1))))
    (testing "拡大も単調性を保つ"
      (is (< (r/at up 0 0) (r/at up 7 7))))
    (testing "8bit の外に出ない"
      (is (every? #(<= 0 % 255) (:px up))))))

(deftest fit-inside-preserves-aspect-and-parity
  (testing "16:9 を縦型 720x1280 に収める —— 幅が支配する"
    (let [[w h] (r/fit-inside 1920 1080 720 1280)]
      (is (= 720 w))
      (is (= 406 h) "1080/1920*720 = 405 -> 偶数丸めで 406")
      (is (even? w)) (is (even? h))))
  (testing "縦長を横型に収める —— 高さが支配する"
    (let [[w h] (r/fit-inside 720 1280 1920 1080)]
      (is (= 1080 h))
      (is (even? w))))
  (testing "同比なら完全一致"
    (is (= [640 360] (r/fit-inside 1280 720 640 360))))
  (testing "4:2:0 の chroma が半分の寸法を取るので必ず偶数"
    (doseq [[sw sh] [[3 7] [101 33] [999 1]]]
      (let [[w h] (r/fit-inside sw sh 720 1280)]
        (is (even? w)) (is (even? h))
        (is (pos? w)) (is (pos? h))))))

(deftest pad-centers-and-fills
  (let [p (r/plane [1 2 3 4] 2 2)
        out (r/plane-pad p 4 4 0)]
    (is (= [4 4] [(:width out) (:height out)]))
    (testing "中央に置かれる"
      (is (= 1 (r/at out 1 1))) (is (= 4 (r/at out 2 2))))
    (testing "余白は fill"
      (is (= 0 (r/at out 0 0))) (is (= 0 (r/at out 3 3))))
    (testing "fill は指定できる"
      (is (= 16 (r/at (r/plane-pad p 4 4 16) 0 0))))))

(deftest fit-is-scale-then-pad
  ;; douga.ffmpeg/scene-segment-cmd の
  ;; scale=W:H:force_original_aspect_ratio=decrease,pad=W:H:... と同じ意味。
  (let [p (ramp 100 50)
        out (r/plane-fit p 64 64)]
    (is (= [64 64] [(:width out) (:height out)]))
    (is (= (* 64 64) (count (:px out))))
    (testing "アスペクト比を保つので上下に余白が出る"
      (is (= 0 (r/at out 32 0)))
      (is (not= 0 (r/at out 32 32))))))

(deftest hold-extends-and-truncates
  (testing "短い列は最後のフレームを複製して伸びる(黒味を残さない)"
    (is (= [:a :b :c :c :c] (r/hold [:a :b :c] 5))))
  (testing "長い列は切り詰める"
    (is (= [:a :b] (r/hold [:a :b :c :d] 2))))
  (testing "ちょうどなら不変"
    (is (= [:a :b] (r/hold [:a :b] 2))))
  (testing "空列は空のまま —— 無いフレームは複製できない"
    (is (= [] (r/hold [] 5))))
  (testing "n<=0 は空"
    (is (= [] (r/hold [:a] 0)))))

(deftest motion-window-matches-its-vocabulary
  (let [W 100 H 100]
    (testing "push-in は寄る: 窓が狭くなる"
      (let [[_ _ w0 _] (r/motion-window :push-in 0.0 W H)
            [_ _ w1 _] (r/motion-window :push-in 1.0 W H)]
        (is (= 100.0 w0)) (is (< w1 w0))))
    (testing "pull-back はその逆"
      (let [[_ _ w0 _] (r/motion-window :pull-back 0.0 W H)
            [_ _ w1 _] (r/motion-window :pull-back 1.0 W H)]
        (is (< w0 w1)) (is (= 100.0 w1))))
    (testing "pan はズームを固定して横に送る"
      (let [[x0 _ w0 _] (r/motion-window :pan-right 0.0 W H)
            [x1 _ w1 _] (r/motion-window :pan-right 1.0 W H)]
        (is (= w0 w1) "パン中はズーム一定")
        (is (< x0 x1) "右へ送る"))
      (let [[x0 _ _ _] (r/motion-window :pan-left 0.0 W H)
            [x1 _ _ _] (r/motion-window :pan-left 1.0 W H)]
        (is (> x0 x1) "左へ送る")))
    (testing "t は 0..1 にクランプされる"
      (is (= (r/motion-window :push-in 0.0 W H) (r/motion-window :push-in -3.0 W H)))
      (is (= (r/motion-window :push-in 1.0 W H) (r/motion-window :push-in 9.0 W H))))
    (testing "窓は必ず平面の内側"
      (doseq [m r/motions, t [0.0 0.25 0.5 0.75 1.0]]
        (let [[x y w h] (r/motion-window m t W H)]
          (is (>= x -0.0001)) (is (>= y -0.0001))
          (is (<= (+ x w) (+ W 0.0001)))
          (is (<= (+ y h) (+ H 0.0001))))))))

(deftest zoompan-produces-n-frames-that-move
  (let [p (ramp 32 32)
        frames (r/zoompan p :push-in 5 16 16)]
    (is (= 5 (count frames)))
    (is (every? #(= [16 16] [(:width %) (:height %)]) frames))
    (testing "実際に動く —— 全フレーム同一なら zoompan ではない"
      (is (not= (:px (first frames)) (:px (last frames)))))
    (testing "1 フレーム要求は t=0 の 1 枚"
      (is (= 1 (count (r/zoompan p :push-in 1 8 8)))))
    (testing "0 フレームは空"
      (is (= [] (r/zoompan p :push-in 0 8 8))))))

(deftest ops-are-declared-pure
  ;; この ns は画素だけを触る。effect を持つ op(decode/encode)は層③の担当で、
  ;; ここに混ぜると policy の gas/effect モデルが嘘になる。
  (is (every? :pure? (vals r/ops)))
  (is (= #{:scale :pad :fit :hold :zoompan} (set (keys r/ops)))))

(deftest describe-is-stable
  (is (= "fit(height=1280 width=720)" (r/describe :fit {:width 720 :height 1280}))))

(deftest raster-ops-are-wired-into-the-policy-model
  ;; 実装だけあって policy に載っていない op は、graph 検査を素通りするか
  ;; 弾かれるかのどちらかで、どちらも壊れている。
  (require 'utsushi.policy)
  (let [effect-of-op @(resolve 'utsushi.policy/effect-of-op)
        gas @(resolve 'utsushi.policy/gas-cost)
        per-frame? @(resolve 'utsushi.policy/per-frame?)]
    (doseq [op (keys r/ops)]
      (testing (str op)
        (is (contains? effect-of-op op) "policy が op を知らない")
        (is (nil? (get effect-of-op op))
            "画素演算に effect を付けると effect モデルが嘘になる")
        (is (pos? (get gas op)) "gas が無いと上限拘束が効かない")))
    (testing "フレームごとに走る op は per-frame? に入る — 漏らすと 1800 フレームの
              graph が 1 フレーム分の見積りで通る"
      (doseq [op [:scale :pad :fit :zoompan]]
        (is (contains? per-frame? op) (str op " must be per-frame")))
      (is (not (contains? per-frame? :hold))
          "hold はフレームを複製するだけで画素を触らない"))
    (testing "filter の gas は encode より 1 桁小さい(実測に対応)"
      (is (< (get gas :fit) (get gas :encode))))))
