(ns utsushi.bytes
  "byte 読み書きの最小プリミティブ（big-endian, ISO BMFF 用）。純 cljc。

  read: vector/seq of bytes（符号付き可。bit-and 0xff で正規化）。
  write: 0..255 の int を要素とする vector を返す（最終 byte 化は mux 側でまとめてマスク）。
  注: write の >2^31 値（巨大オフセット）は cljs の 32bit bitops で破綻しうる。R0 はファイル
  サイズが小さい前提（4GB 未満）。真の大容量は 64bit largesize/co64 で別途。")

;; ---- read (big-endian) ----
(defn u8  [b i] (bit-and (int (nth b i)) 0xff))
(defn u16 [b i] (bit-or (bit-shift-left (u8 b i) 8) (u8 b (+ i 1))))
(defn u24 [b i] (bit-or (bit-shift-left (u8 b i) 16)
                        (bit-shift-left (u8 b (+ i 1)) 8)
                        (u8 b (+ i 2))))
(defn u32 [b i] (bit-or (bit-shift-left (u8 b i) 24)
                        (bit-shift-left (u8 b (+ i 1)) 16)
                        (bit-shift-left (u8 b (+ i 2)) 8)
                        (u8 b (+ i 3))))
(defn u64 [b i] (+ (* (u32 b i) 4294967296) (u32 b (+ i 4))))
(defn ascii4 [b i] (apply str (map #(char (u8 b (+ i %))) (range 4))))

;; ---- write (vector of byte-valued ints) ----
(defn wu8  [n] [(bit-and n 0xff)])
(defn wu16 [n] [(bit-and (bit-shift-right n 8) 0xff)
                (bit-and n 0xff)])
(defn wu24 [n] [(bit-and (bit-shift-right n 16) 0xff)
                (bit-and (bit-shift-right n 8) 0xff)
                (bit-and n 0xff)])
(defn wu32 [n] [(bit-and (bit-shift-right n 24) 0xff)
                (bit-and (bit-shift-right n 16) 0xff)
                (bit-and (bit-shift-right n 8) 0xff)
                (bit-and n 0xff)])
(defn wstr [s] (mapv int s))
