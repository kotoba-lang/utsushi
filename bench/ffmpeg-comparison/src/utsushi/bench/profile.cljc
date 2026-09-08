(ns utsushi.bench.profile
  "Where utsushi's per-frame time actually goes — a sampling profile, not a guess.

  The comparison bench answers HOW MUCH slower utsushi is than ffmpeg. It
  cannot answer WHERE that time is spent, and at tens to hundreds of
  milliseconds of CPU per frame the gap is very unlikely to be uniform.
  Guessing which of entropy decode, the transforms, or boxed arithmetic
  dominates would direct every later decision off a hunch.

  This runs the same decode the bench times, under a JFR execution-sample
  recording, and aggregates the samples two ways:

  - by the LEAF frame — where the CPU actually is;
  - by the deepest frame inside `h264.*` / `utsushi.*` / `isobmff.*`, which
    attributes JDK-internal leaves (`clojure.lang.*`, boxing, hashing) back to
    the decoder code that called them.

  A sampling profile is a distribution over samples, not a measurement of any
  one call. The sample count is printed and compared against `sample-floor`, so
  a thin profile says it is thin instead of letting a 3% entry that is two
  samples be quoted. It times nothing and gates nothing, and it is deliberately
  NOT run inside the comparison: a profiler attached to a timed interval
  measures the profiler.

  ## Why every form here is `#?(:clj …)`

  This file is JVM-only and says so rather than pretending otherwise. JFR is a
  JVM facility, and the arm it profiles is the JVM incumbent oracle — which
  ADR-2607198300 names as explicitly NOT the destination. When a kotoba backend
  can run the decoder, profiling it will need a different instrument against a
  different runtime, not a port of this one. Writing that as a reader
  conditional keeps the fact in the source instead of in a reader's memory.

  Run: `clojure -M:bench-profile [fixture.mp4] [iterations]`"
  #?(:clj (:require [clojure.java.io :as io]
                    [kotoba.lang.text :as str]))
  #?(:clj (:import [jdk.jfr Recording]
                   [jdk.jfr.consumer RecordingFile RecordedEvent RecordedFrame]))
  #?(:clj (:gen-class)))

#?(:clj
   (defn- read-bytes [^java.io.File f]
     (with-open [in (io/input-stream f)]
       (mapv #(bit-and (int %) 0xff) (.readAllBytes in)))))

#?(:clj (def ^:private own-prefixes ["h264." "utsushi." "isobmff."]))

#?(:clj
   (defn- own? [^String cls]
     (boolean (some #(str/starts-with? cls ^String %) own-prefixes))))

#?(:clj
   (defn- frame-name [^RecordedFrame fr]
     (let [m (.getMethod fr)]
       (str (.getName (.getType m)) "/" (.getName m)))))

#?(:clj
   (defn- tally
     "Sample counts by leaf frame and by deepest own frame, as data."
     [^java.io.File jfr]
     (with-open [rf (RecordingFile. (.toPath jfr))]
       (loop [leaf {} own {} n 0]
         (if-not (.hasMoreEvents rf)
           {:samples n :by-leaf leaf :by-own-frame own}
           (let [^RecordedEvent e (.readEvent rf)]
             (if-not (= "jdk.ExecutionSample" (.getName (.getEventType e)))
               (recur leaf own n)
               (let [names (mapv frame-name (.getFrames (.getStackTrace e)))
                     l (first names)
                     o (first (filter own? names))]
                 (recur (update leaf l (fnil inc 0))
                        (if o (update own o (fnil inc 0)) own)
                        (inc n))))))))))

#?(:clj
   (def sample-floor
     "Below this many execution samples the percentages are shape, not
     measurement. Measured: 5 decodes of the 3-frame 320x240 fixture yielded 64
     samples, at which a 3% entry is two samples. The floor is printed and named
     rather than left to the reader to notice a small n."
     200))

#?(:clj
   (defn- print-table [title total m limit]
     (println)
     (println title)
     (if (zero? total)
       (println "  NO SAMPLES — this profile measured nothing; do not quote it")
       (doseq [[k v] (take limit (sort-by (comp - val) m))]
         (println (format "  %5.1f%%  %6d  %s" (* 100.0 (/ (double v) total)) v k))))))

#?(:clj
   (defn -main [& [fixture iters]]
     (let [decode (requiring-resolve 'utsushi.pipeline.mp4-h264/decode-h264-frames)
           load1 (requiring-resolve 'utsushi.bench.host/load1)
           f (io/file "bench/ffmpeg-comparison/fixtures" (or fixture "gop320x240.mp4"))]
       (when-not (.isFile f)
         (println "no such fixture:" (.getPath f))
         (System/exit 3))
       (let [mp4 (read-bytes f)
             n (Long/parseLong (or iters "3"))
             jfr (java.io.File/createTempFile "utsushi-profile" ".jfr")
             rec (Recording.)]
         (dotimes [_ 3] (decode mp4))              ; warm-up outside the recording
         (doto rec
           (.setSettings {"jdk.ExecutionSample#enabled" "true"
                          "jdk.ExecutionSample#period" "1 ms"})
           (.setMaxSize 512000000)
           (.start))
         (dotimes [_ n] (decode mp4))
         (.stop rec)
         (.dump rec (.toPath jfr))
         (.close rec)
         (let [{:keys [samples by-leaf by-own-frame]} (tally jfr)]
           (println (format "fixture %s   decodes recorded %d   execution samples %d   load1 %s"
                            (.getName f) n samples (str (load1))))
           (when (< samples sample-floor)
             (println (format "THIN PROFILE: %d samples < floor %d — raise the iteration count; do not quote these percentages"
                              samples sample-floor)))
           (print-table "BY LEAF FRAME — where the CPU is" samples by-leaf 20)
           (print-table "BY DEEPEST DECODER FRAME — which decoder code spends it" samples by-own-frame 20)
           (.delete jfr)
           (when (zero? samples)
             (println "REFUSING to report a profile of nothing")
             (System/exit 3)))))))
