(ns utsushi.blob
  "content-address（CID 風）の最小実装。

  R0 placeholder: FNV-1a 32bit hex。決定論的で、同一バイト列 → 同一 id。実運用では
  Vault の blake3 BlobManifest CID に置き換える（ADR-2606272200 §5）。ここでは『フレーム/
  パケットを CID 参照で扱う』という設計境界を成立させることが目的で、暗号学的強度は問わない。")

(defn content-id
  "byte 列の決定論的 content id 文字列。`uts-fnv1a32-<8hex>`。"
  [bytes]
  (let [;; FNV-1a 32bit: h = (h XOR byte) * 16777619（= h<<24+h<<8+h<<7+h<<4+h<<1+h）
        h (reduce
           (fn [h x]
             (let [h (bit-xor h (bit-and (int x) 0xff))]
               (bit-and (+ h (bit-shift-left h 1) (bit-shift-left h 4)
                           (bit-shift-left h 7) (bit-shift-left h 8)
                           (bit-shift-left h 24))
                        0xffffffff)))
           2166136261
           bytes)
        digits "0123456789abcdef"
        hex8 (apply str (map (fn [shift]
                               (nth digits (bit-and (unsigned-bit-shift-right h shift) 0xf)))
                             [28 24 20 16 12 8 4 0]))]
    (str "uts-fnv1a32-" hex8)))
