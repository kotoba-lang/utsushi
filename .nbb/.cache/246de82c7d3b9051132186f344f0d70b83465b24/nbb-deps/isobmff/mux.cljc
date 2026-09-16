(ns isobmff.mux
  "Minimal ISO BMFF (MP4) writer, for re-encode-free remux output. Lays out
   `ftyp + mdat + moov` (mdat-first) so chunk offsets compute in one pass
   (no two-pass patching). Normalizes to 1 track = 1 chunk (all samples laid
   out contiguously). stsd is re-emitted verbatim from whatever
   isobmff.demux captured (codec config stays opaque passthrough).
   R0: 32-bit box sizes, no edit list, width/height left 0 (structurally
   valid, dimensions unset).

   Extracted from kotoba-lang/utsushi (utsushi.mux, ADR-2606272200) as part
   of `org-iso-isobmff`."
  (:require [isobmff.bytes :as b]))

(defn- box [type payload]
  (let [p (vec payload), size (+ 8 (count p))]
    (into (into (b/wu32 size) (b/wstr type)) p)))

(defn- fbox [type version flags payload]
  (box type (concat (b/wu8 version) (b/wu24 flags) payload)))

(def ^:private identity-matrix
  (mapcat b/wu32 [0x00010000 0 0 0 0x00010000 0 0 0 0x40000000]))

(def ^:private vmhd (fbox "vmhd" 0 1 (concat (b/wu16 0) (b/wu16 0) (b/wu16 0) (b/wu16 0))))
(def ^:private smhd (fbox "smhd" 0 0 (concat (b/wu16 0) (b/wu16 0))))
(def ^:private dref (fbox "dref" 0 0 (concat (b/wu32 1) (fbox "url " 0 1 []))))
(def ^:private dinf (box "dinf" dref))

(defn- mvhd [timescale duration next-track]
  (fbox "mvhd" 0 0
        (concat (b/wu32 0) (b/wu32 0) (b/wu32 timescale) (b/wu32 duration)
                (b/wu32 0x00010000) (b/wu16 0x0100) (b/wu16 0) (b/wu32 0) (b/wu32 0)
                identity-matrix (repeat 24 0) (b/wu32 next-track))))

(defn- tkhd [track-id duration]
  (fbox "tkhd" 0 7
        (concat (b/wu32 0) (b/wu32 0) (b/wu32 track-id) (b/wu32 0) (b/wu32 duration)
                (b/wu32 0) (b/wu32 0) (b/wu16 0) (b/wu16 0) (b/wu16 0) (b/wu16 0)
                identity-matrix (b/wu32 0) (b/wu32 0))))

(defn- mdhd [timescale duration]
  (fbox "mdhd" 0 0 (concat (b/wu32 0) (b/wu32 0) (b/wu32 timescale) (b/wu32 duration)
                           (b/wu16 0x55c4) (b/wu16 0))))

(defn- hdlr [handler]
  (fbox "hdlr" 0 0 (concat (b/wu32 0) (b/wstr handler) (repeat 12 0)
                           (b/wstr "isobmff") (b/wu8 0))))

(defn- stts [deltas]
  (let [runs (map (fn [g] [(count g) (first g)]) (partition-by identity deltas))]
    (fbox "stts" 0 0 (concat (b/wu32 (count runs))
                             (mapcat (fn [[c d]] (concat (b/wu32 c) (b/wu32 d))) runs)))))

(defn- stsz [sizes]
  (fbox "stsz" 0 0 (concat (b/wu32 0) (b/wu32 (count sizes)) (mapcat b/wu32 sizes))))

(defn- stsc [n]
  (fbox "stsc" 0 0 (concat (b/wu32 1) (b/wu32 1) (b/wu32 n) (b/wu32 1))))

(defn- stco [offset]
  (fbox "stco" 0 0 (concat (b/wu32 1) (b/wu32 offset))))

(defn- stss [idxs]
  (fbox "stss" 0 0 (concat (b/wu32 (count idxs)) (mapcat b/wu32 (sort idxs)))))

(defn- trak [track chunk-offset]
  (let [{:keys [track-id handler timescale samples stsd]} track
        deltas    (mapv :duration samples)
        sizes     (mapv :size samples)
        duration  (reduce + 0 deltas)
        all-sync? (every? :keyframe samples)
        sync-idxs (keep-indexed (fn [i s] (when (:keyframe s) (inc i))) samples)
        stbl (box "stbl" (concat stsd
                                 (stts deltas) (stsz sizes) (stsc (count samples))
                                 (stco chunk-offset)
                                 (when-not all-sync? (stss sync-idxs))))
        mh   (if (= handler "soun") smhd vmhd)
        minf (box "minf" (concat mh dinf stbl))
        mdia (box "mdia" (concat (mdhd timescale duration) (hdlr handler) minf))]
    {:bytes (box "trak" (concat (tkhd track-id duration) mdia))
     :duration duration}))

(def ^:private ftyp
  (box "ftyp" (concat (b/wstr "isom") (b/wu32 512) (b/wstr "isom") (b/wstr "mp41"))))

(defn minimal-stsd
  "Minimal stsd box (one opaque sample entry). For R0 bootstrap/testing —
   carries no codec config (stsd is opaque per this repo's design). A real
   demux round-trip uses the source file's stsd verbatim instead."
  [fourcc]
  (let [entry (box fourcc (concat (repeat 6 0) (b/wu16 1) (repeat 16 0)))]
    (fbox "stsd" 0 0 (concat (b/wu32 1) entry))))

(defn mux
  "demux structure {:timescale :tracks} → MP4 byte vector (ftyp + mdat +
   moov). Re-encode-free."
  [{:keys [tracks timescale]}]
  (let [timescale       (or timescale (:timescale (first tracks)) 1000)
        track-payloads  (mapv (fn [t] (vec (mapcat :bytes (:samples t)))) tracks)
        mdat-payload    (vec (apply concat track-payloads))
        mdat-data-start (+ (count ftyp) 8)
        offsets         (reductions + mdat-data-start (map count track-payloads))
        traks           (map trak tracks offsets)
        movie-duration  (reduce max 0 (map :duration traks))
        moov (box "moov" (concat (mvhd timescale movie-duration (inc (count tracks)))
                                 (mapcat :bytes traks)))
        mdat (box "mdat" mdat-payload)]
    (mapv #(bit-and (int %) 0xff) (concat ftyp mdat moov))))
