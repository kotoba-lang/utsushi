(ns utsushi.quads
  "横断: フレーム/パケット/メタを kotoba Datom（`media/*` 述語）へ射影する。

  設計: ADR-2606272200 §5/§6。生フレームは Vault blob（CodecAware チャンク, BlobManifest
  CID）として持ち、ここでは CID + 小さなメタ（解像度/pts/codec）だけを datom 化する。
  既存 kotoba-ingest `media/*` を `media/stream/{idx}` `media/packet/{n}` `media/frame/{pts}`
  に拡張する射影。")

(defn stream-quads
  "demux 結果の elementary stream を `media/stream/*` datom 列へ射影。"
  [graph stream-idx {:keys [codec width height duration]}]
  (let [s (str "media/stream/" stream-idx)]
    (cond-> [[graph s "codec" (name codec)]]
      width    (conj [graph s "width"    width])
      height   (conj [graph s "height"   height])
      duration (conj [graph s "duration" duration]))))

(defn packet-quads
  "パケット境界（blob CID）を `media/packet/{n}` datom へ射影。実体はインラインしない。"
  [graph n {:keys [cid pts dts keyframe]}]
  (let [p (str "media/packet/" n)]
    (cond-> [[graph p "blob" cid]]
      pts      (conj [graph p "pts" pts])
      dts      (conj [graph p "dts" dts])
      keyframe (conj [graph p "keyframe" true]))))
