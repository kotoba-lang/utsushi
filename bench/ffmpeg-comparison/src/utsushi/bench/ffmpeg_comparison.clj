(ns utsushi.bench.ffmpeg-comparison
  "utsushi vs ffmpeg, on decoding the same H.264 pictures out of the same MP4.

  Modelled on `kotoba-lang/amu`'s `bench/runtime-comparison/` and its
  ADR 0226 (`cross-language runtime evidence is workload-bound`): a
  comparison is meaningless unless both engines computed the same
  observable result, and a report is meaningless unless it binds the host,
  the toolchain version, the fixture and the frame count to the number.

  ## The engine set is a roster, not a pair

  ADR 0226's harness is a set of ADAPTERS, and an adapter that cannot run
  is recorded by name with a reason (`skippedEngines`) rather than being
  absent. This one keeps that discipline for the same reason:

  | engine | role | today |
  |---|---|---|
  | `ffmpeg` | reference | measured |
  | `utsushi-jvm` | **incumbent oracle** — full Clojure on the JVM | measured |
  | `kotoba-wasm32` | target backend | unavailable, stage recorded |
  | `kotoba-native-aarch64` | target backend | unavailable, stage recorded |
  | `kotoba-script-js` | target backend | unavailable, stage recorded |

  **The JVM arm is the incumbent, not the destination.** ADR-2607198300
  says the destination is an artifact that needs no JVM, Node or Rust at
  run time. A two-engine utsushi-vs-ffmpeg table would quietly imply the
  JVM path is where this ends. Naming the kotoba backends and reporting
  the exact stage each stops at is what keeps the remaining distance
  visible — see `utsushi.bench.engines`, which measures that distance by
  running `amu` rather than asserting it.

  ## The five rules this harness exists to enforce

  1. **Same observable result or no comparison.** Before either engine is
     timed, both decode the fixture and their yuv420p bytes must be
     identical. A fixture whose engines disagree is reported as a
     `:mismatch` and NOTHING about it is timed — a speed ratio between two
     different answers is not a speed ratio.
  2. **ffmpeg absent must not look like a pass.** It is recorded as a
     skipped engine with a reason and the process exits `3` — neither `0`
     (measured, clean) nor `1` (measured, mismatch), because
     `could not measure` must not return the same value as either.
  3. **An evidence floor.** The report states how many samples were taken
     and how many fixtures were actually compared. Zero of either is an
     explicit `:refused` verdict, never a clean run with an empty table.
  4. **Load next to every number.** `load1` is read before and after each
     engine's samples. amu ADR 0281 (2026-08-29) measured that no host in
     this fleet reaches `load1 <= 1.0`, so no run here can claim a quiet
     machine; it states the load it ran at instead.
  5. **The statistics are `perfgate`'s.** `perfgate.core/qualify` decides
     whether a difference may be claimed. This namespace does not compute a
     mean and call it a result.

  ## Why steady-state per-frame is a slope, not a division

  An ffmpeg invocation is a process: `exec`, dynamic linking, demux setup
  and teardown swamp the decode of a handful of small frames. A utsushi
  invocation is a JIT'd JVM method. Dividing either engine's wall time by
  its frame count therefore measures mostly the fixed cost, and on a tiny
  fixture that flatters utsushi so badly the comparison inverts — measured:
  at 32x32 the naive division made utsushi look 1.6x FASTER than ffmpeg.

  So each fixture is decoded at TWO frame counts — the fixture itself, and
  the same fixture concatenated N times through
  `utsushi.pipeline.remux/concat-mp4s` (stream copy, no re-encode) — and the
  per-frame cost is the SLOPE between them, at which the fixed cost cancels.

  **The two engines get different N** (see `fixtures.edn`), because a slope
  only resolves a per-frame cost when the added frames cost more than the
  invocation's own jitter, and the engines' fixed and marginal costs are
  four orders of magnitude apart. At a shared stretch of 2 ffmpeg's slope
  came out NEGATIVE — its per-frame cost was entirely inside its process
  startup's variation. A negative per-frame cost is nonsense that the
  arithmetic will happily produce and the report will happily print, so a
  non-positive slope is reported as `:unresolved-below-fixed-cost` and no
  ratio is derived from it.

  Both numbers are reported: the slope as steady state, the wall time per
  invocation separately, as ADR 0226 requires of the amu harness.

  ## Boundaries this number does not cross

  - ffmpeg is timed as a subprocess writing to `-f null -`: it decodes and
    discards. utsushi builds Clojure vectors of pixels. That difference
    favours ffmpeg and is not corrected for.
  - The fixtures are flat, DC-heavy, Intra_16x16 content, because that is
    the only kind of real libx264 output `org-iso-h264` decodes at all (see
    `utsushi.pipeline.mp4-h264`'s docstring). These numbers describe that
    workload. They are not a general video decode ranking, and no amount of
    sampling makes them one.
  - utsushi's number excludes JVM startup and runs after warm-up. ffmpeg's
    slope excludes process startup by construction. Neither includes the
    other's fixed cost.

  Run: `clojure -M:bench` (add `--samples N`, `--stretch N`, `--out FILE`)."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.java.shell :as sh]
            [clojure.pprint :as pp]
            [perfgate.core :as g]
            [utsushi.bench.host :as host]
            [utsushi.bench.engines :as engines]
            [isobmff.demux]
            [utsushi.pipeline.mp4-h264 :as pipeline]
            [utsushi.pipeline.remux :as remux])
  (:gen-class))

;; ── exit codes ───────────────────────────────────────────────────────────
;;
;; Three values, because there are three outcomes and collapsing any two of
;; them is the failure this harness is built to avoid.

(def exit-measured 0)
(def exit-mismatch 1)
(def exit-could-not-measure 3)

;; ── ffmpeg ───────────────────────────────────────────────────────────────

(defn ffmpeg-binary
  "Path to ffmpeg, or nil. Checked once; a later disappearance shows up as a
  failed invocation rather than a silent skip."
  []
  (let [{:keys [exit out]} (sh/sh "sh" "-c" "command -v ffmpeg")]
    (when (zero? exit) (str/trim out))))

(defn ffmpeg-provenance
  "ffmpeg's version and build, read off the binary. `:unavailable` with a
  reason when it is not there — never an empty map that reads like a
  measurement."
  [bin]
  (if-not bin
    {:status :unavailable
     :reason "no ffmpeg on PATH (command -v ffmpeg found nothing)"}
    (let [{:keys [exit out err]} (sh/sh bin "-version")]
      (if-not (zero? exit)
        {:status :unavailable :reason (str "ffmpeg -version exited " exit ": " err)}
        (let [lines (str/split-lines out)]
          {:status :measured
           :binary bin
           :version (first lines)
           :build (first (filter #(str/starts-with? % "configuration:") lines))})))))

(defn- write-bytes! [^java.io.File f byte-vec]
  (with-open [o (io/output-stream f)]
    (.write o (byte-array (map unchecked-byte byte-vec)))))

(defn- read-bytes [^java.io.File f]
  (with-open [in (io/input-stream f)]
    (mapv #(bit-and (int %) 0xff) (.readAllBytes in))))

(defn ffmpeg-decode-to-yuv
  "ffmpeg's own reconstructed pixels for `mp4-file`, as a yuv420p byte
  vector. Untimed — this is the ground truth for the equal-result check."
  [bin mp4-file]
  (let [out (java.io.File/createTempFile "utsushi-bench-ref" ".yuv")]
    (try
      (let [{:keys [exit err]} (sh/sh bin "-y" "-v" "error" "-i" (.getPath mp4-file)
                                      "-pix_fmt" "yuv420p" "-f" "rawvideo" (.getPath out))]
        (when-not (zero? exit)
          (throw (ex-info "ffmpeg reference decode failed" {:exit exit :stderr err})))
        (read-bytes out))
      (finally (.delete out)))))

(defn time-ffmpeg-decode-ms
  "Wall milliseconds for one ffmpeg subprocess decoding `mp4-file` to
  `-f null -` (decode, discard). Includes process startup by construction;
  the slope removes it."
  [bin mp4-file]
  (let [t0 (System/nanoTime)
        {:keys [exit err]} (sh/sh bin "-v" "error" "-i" (.getPath mp4-file) "-f" "null" "-")
        t1 (System/nanoTime)]
    (when-not (zero? exit)
      (throw (ex-info "ffmpeg timed decode failed" {:exit exit :stderr err})))
    (/ (- t1 t0) 1e6)))

;; ── utsushi ──────────────────────────────────────────────────────────────

(defn utsushi-decode-to-yuv
  "utsushi's decoded pixels in the same flat yuv420p layout ffmpeg writes,
  so the two are comparable as byte vectors with no adapter in between."
  [mp4-bytes]
  (vec (mapcat (fn [f] (concat (:luma f) (:cb f) (:cr f)))
               (pipeline/decode-h264-frames mp4-bytes))))

(defn time-utsushi-decode-ms
  "Wall milliseconds for one in-process utsushi decode. Excludes JVM startup
  and is taken after warm-up; the slope removes the remaining fixed cost."
  [mp4-bytes]
  (let [t0 (System/nanoTime)
        frames (pipeline/decode-h264-frames mp4-bytes)
        t1 (System/nanoTime)]
    ;; touch the result so nothing about it can be elided
    (when (zero? (count frames))
      (throw (ex-info "utsushi decoded zero frames" {})))
    (/ (- t1 t0) 1e6)))

;; ── one fixture ──────────────────────────────────────────────────────────

(defn- frame-count [mp4-bytes]
  (count (:samples (pipeline/video-track (isobmff.demux/demux (vec mp4-bytes))))))

(defn- slopes
  "Per-frame milliseconds, sample by sample, as the slope between the short
  and the stretched stream. Pairing the i-th sample of each keeps the two
  arms of one slope adjacent in time, so a load excursion moves both."
  [short-samples long-samples short-frames long-frames]
  (let [dn (- long-frames short-frames)]
    (when-not (pos? dn)
      (throw (ex-info "the stretched stream is not longer than the short one"
                      {:short-frames short-frames :long-frames long-frames})))
    (mapv (fn [s l] (/ (- l s) (double dn))) short-samples long-samples)))

(defn compare-fixture
  "Decode one fixture with both engines, check they agree, then time both.

  Returns a map that always says what happened — `:compared`, `:mismatch`,
  or `:error` — and never omits a fixture it could not handle.

  ## What the equal-result check does and does not cover

  utsushi and ffmpeg are compared pixel-for-pixel on the fixture itself and
  on the utsushi-stretched stream. ffmpeg's much longer stretch is NOT
  decoded by utsushi — at these per-frame costs that would take the better
  part of an hour per fixture. It is checked a different way that costs
  nothing: a concatenation of N copies of a self-contained GOP must decode
  to the short decode repeated N times, and ffmpeg's own long output is
  asserted to be exactly that. So the chain is
  `utsushi(short) = ffmpeg(short)` and `ffmpeg(long) = ffmpeg(short) x N`,
  which is a real check rather than an assumption dressed as one."
  [{:keys [ffmpeg-bin fixture-file samples utsushi-stretch ffmpeg-stretch]}]
  (let [mp4 (read-bytes fixture-file)
        short-frames (frame-count mp4)
        u-long-mp4 (remux/concat-mp4s (repeat utsushi-stretch mp4))
        f-long-mp4 (remux/concat-mp4s (repeat ffmpeg-stretch mp4))
        u-long-frames (frame-count u-long-mp4)
        f-long-frames (frame-count f-long-mp4)
        u-long-file (java.io.File/createTempFile "utsushi-bench-ulong" ".mp4")
        f-long-file (java.io.File/createTempFile "utsushi-bench-flong" ".mp4")]
    (try
      (write-bytes! u-long-file u-long-mp4)
      (write-bytes! f-long-file f-long-mp4)
      (let [ref-short (ffmpeg-decode-to-yuv ffmpeg-bin fixture-file)
            ref-u-long (ffmpeg-decode-to-yuv ffmpeg-bin u-long-file)
            ref-f-long (ffmpeg-decode-to-yuv ffmpeg-bin f-long-file)
            got-short (utsushi-decode-to-yuv mp4)
            got-u-long (utsushi-decode-to-yuv u-long-mp4)
            agree-short? (= ref-short got-short)
            agree-long? (= ref-u-long got-u-long)
            ;; the cheap check standing in for decoding ffmpeg's long stream
            ;; with utsushi: N copies of a self-contained GOP must decode to
            ;; N copies of its pixels.
            long-is-repetition? (= (vec (apply concat (repeat ffmpeg-stretch ref-short)))
                                   ref-f-long)]
        (if-not (and agree-short? agree-long? long-is-repetition?)
          {:status :mismatch
           :fixture (.getName fixture-file)
           :short-frames short-frames
           :utsushi-long-frames u-long-frames :ffmpeg-long-frames f-long-frames
           :agree-short? agree-short? :agree-long? agree-long?
           :ffmpeg-long-is-repetition? long-is-repetition?
           :note "engines produced different pixels (or the concatenation is not a repetition); nothing was timed"}
          (let [load-before (host/load1)
                ;; warm-up sits outside every timed interval, for both engines
                _ (dotimes [_ 2] (time-utsushi-decode-ms mp4))
                _ (time-ffmpeg-decode-ms ffmpeg-bin fixture-file)
                u-short (vec (repeatedly samples #(time-utsushi-decode-ms mp4)))
                u-long (vec (repeatedly samples #(time-utsushi-decode-ms u-long-mp4)))
                f-short (vec (repeatedly samples #(time-ffmpeg-decode-ms ffmpeg-bin fixture-file)))
                f-long (vec (repeatedly samples #(time-ffmpeg-decode-ms ffmpeg-bin f-long-file)))
                load-after (host/load1)]
            {:status :compared
             :fixture (.getName fixture-file)
             :short-frames short-frames
             :utsushi-long-frames u-long-frames
             :ffmpeg-long-frames f-long-frames
             :utsushi-stretch utsushi-stretch
             :ffmpeg-stretch ffmpeg-stretch
             :yuv-bytes (count ref-short)
             :load1-before load-before
             :load1-after load-after
             :utsushi {:wall-ms-short u-short :wall-ms-long u-long
                       :per-frame-ms (slopes u-short u-long short-frames u-long-frames)}
             :ffmpeg {:wall-ms-short f-short :wall-ms-long f-long
                      :per-frame-ms (slopes f-short f-long short-frames f-long-frames)}})))
      (catch Exception e
        {:status :error :fixture (.getName fixture-file)
         :message (.getMessage e) :data (ex-data e)})
      (finally (.delete u-long-file) (.delete f-long-file)))))


;; ── the gate ─────────────────────────────────────────────────────────────

(defn resolved?
  "Did this engine's slope resolve a per-frame cost at all?

  A non-positive slope means the added frames cost less than the
  invocation's own jitter — the measurement did not reach the quantity. It
  is NOT a fast engine, and it must not be divided into anything."
  [samples]
  (pos? (/ (reduce + 0.0 samples) (max 1 (count samples)))))

(defn qualify-fixture
  "Hand both arms to `perfgate` and keep whatever it says.

  Asked in BOTH directions on purpose. `utsushi as candidate` is the
  question anyone actually wants answered and is expected to be refused —
  pure cljc on the JVM against hand-written C with SIMD is not a close
  race. `ffmpeg as candidate` is the control: a gate that only ever refuses
  is indistinguishable from a gate that is broken, so the report has to show
  what it does when handed a real difference."
  [result machine]
  (let [obs (fn [id engine long-frames stretch]
              (g/observation
               {:id id
                :plan-id (keyword (str/replace (:fixture result) #"\.mp4$" ""))
                :machine machine
                :metric :steady-state-decode-ms-per-frame
                :unit :ms
                :lower-is-better? true
                :samples (get-in result [engine :per-frame-ms])
                :source (str "slope between " (:short-frames result) "- and "
                             long-frames "-frame decodes (stream-copy concat x"
                             stretch ") of " (:fixture result) "; "
                             (case engine
                               :utsushi "in-process utsushi.pipeline.mp4-h264/decode-h264-frames, post-warm-up"
                               :ffmpeg "ffmpeg -v error -i FIXTURE -f null - subprocess wall time")
                             "; load1 before=" (:load1-before result)
                             " after=" (:load1-after result))}))
        u (obs :utsushi/steady-state :utsushi
               (:utsushi-long-frames result) (:utsushi-stretch result))
        f (obs :ffmpeg/steady-state :ffmpeg
               (:ffmpeg-long-frames result) (:ffmpeg-stretch result))
        um (get-in u [:observation/summary :mean])
        fm (get-in f [:observation/summary :mean])
        both-resolved? (and (resolved? (get-in result [:utsushi :per-frame-ms]))
                            (resolved? (get-in result [:ffmpeg :per-frame-ms])))]
    {:utsushi-observation u
     :ffmpeg-observation f
     :utsushi-as-candidate (g/qualify u f)
     :ffmpeg-as-candidate (g/qualify f u)
     :minimum-detectable (g/minimum-detectable-improvement f u)
     :resolution (cond
                   both-resolved? :resolved
                   (not (resolved? (get-in result [:ffmpeg :per-frame-ms])))
                   :ffmpeg-unresolved-below-fixed-cost
                   :else :utsushi-unresolved-below-fixed-cost)
     :slowdown-factor (when both-resolved? (/ um fm))}))

;; ── report ───────────────────────────────────────────────────────────────

(defn- fmt [x] (if (number? x) (format "%.3f" (double x)) "n/a"))

(defn- reason-lines
  "perfgate's refusals, WITH the arm each one is about.

  `perfgate.core/explain` drops `:arm`, so two identical-looking
  `insufficient-samples` lines are actually about different observations.
  Losing that is the sort of detail that turns a report into folklore."
  [label verdict]
  (into [(str label " " (if (:qualified? verdict) "QUALIFIED" "REFUSED")
               (when-let [i (:improvement verdict)]
                 (str "  (improvement " (fmt (* 100.0 i)) "%)")))]
        (mapv (fn [r]
                (str label "   - " (name (:reason r))
                     (when (:arm r) (str " [" (name (:arm r)) "]"))
                     (cond
                       (= :insufficient-samples (:reason r))
                       (str ": n=" (:n r) " < " (:required r))
                       (= :too-noisy (:reason r))
                       (str ": relative stdev " (fmt (:relative-stdev r))
                            " > " (:allowed r))
                       (= :not-separated-from-noise (:reason r))
                       (str ": gap " (fmt (:gap r))
                            " <= summed stdev " (fmt (:summed-stdev r)))
                       (= :improvement-below-threshold (:reason r))
                       (str ": " (fmt (* 100.0 (:improvement r))) "% < "
                            (fmt (* 100.0 (:required r))) "%")
                       :else (str (when (:note r) (str ": " (:note r)))))))
              (:reasons verdict))))

(defn render-lines [report]
  (let [{:keys [host-summary ffmpeg evidence results verdicts]} report]
    (concat
     ["=== utsushi vs ffmpeg — H.264 decode of the same MP4 ==="
      (str "host              " (:machine-id host-summary))
      (str "provenance        " (:provenance host-summary)
           "  fingerprint " (:fingerprint host-summary))
      (str "cores             " (:cores host-summary)
           "  clusters " (pr-str (:clusters host-summary)))
      (str "host probe        " (:source host-summary))
      (str "ffmpeg            " (or (:version ffmpeg) (:reason ffmpeg)))
      (str "workload          flat Intra_16x16 / P_Skip / P_L0_16x16 baseline H.264 "
           "— the only real libx264 output org-iso-h264 decodes. NOT general video.")
      ""
      "ENGINES — an adapter that cannot run is named and explained, never omitted"]
     (mapcat
      (fn [e]
        (into [(str "  " (name (:engine/id e))
                    "  [" (name (:engine/role e)) "]"
                    "  status " (name (:status e))
                    "  stage " (name (or (:stage e) :cli))
                    (when (:frontier-stage e)
                      (str "  (furthest stage any h264 namespace reaches: "
                           (name (:frontier-stage e)) ")")))]
              (keep identity
                    [(when (:version e) (str "      " (:version e)))
                     (when (:build e) (str "      " (:build e)))
                     (when (:note e) (str "      note: " (:note e)))
                     (when (:reason e) (str "      reason: " (:reason e)))
                     (when-let [gns (get-in e [:evidence :guest-grammar-namespaces])]
                       (str "      guest-grammar namespaces present: " (count gns)
                            " of " (get-in e [:evidence :total-namespaces])
                            " " (pr-str gns)))])))
      (:engines report))
     [""
      (str "EVIDENCE  engines measurable " (:engines-measurable evidence)
           " of " (:engines-in-roster evidence)
           " | fixtures compared " (:fixtures-compared evidence)
           " of " (:fixtures-attempted evidence)
           " | timed samples taken " (:samples-taken evidence)
           " | samples per arm " (:samples-per-arm evidence)
           " | mismatches " (:mismatches evidence)
           " | errors " (:errors evidence))
      (str "VERDICT   " (name (:verdict report))
           (when (:reason report) (str " (" (name (:reason report)) ")"))
           (when (:note report) (str " — " (:note report))))
      ""]
     (mapcat
      (fn [{:keys [result gate]}]
        (if (not= :compared (:status result))
          [(str "  " (:fixture result) ": " (name (:status result)) " — "
                (or (:note result) (:message result)))
           ""]
          (let [u (get-in gate [:utsushi-observation :observation/summary])
                f (get-in gate [:ffmpeg-observation :observation/summary])]
            (concat
             [(str "  " (:fixture result)
                   "   yuv bytes/frame-set " (:yuv-bytes result)
                   "   load1 " (:load1-before result) " -> " (:load1-after result))
              (str "    frame counts            utsushi " (:short-frames result)
                   "->" (:utsushi-long-frames result)
                   " (concat x" (:utsushi-stretch result) ")"
                   "   ffmpeg " (:short-frames result)
                   "->" (:ffmpeg-long-frames result)
                   " (concat x" (:ffmpeg-stretch result) ")")
              (str "    steady-state ms/frame   utsushi mean " (fmt (:mean u))
                   " sd " (fmt (:stdev u)) " median " (fmt (:median u))
                   " n " (:n u))
              (str "                            ffmpeg  mean " (fmt (:mean f))
                   " sd " (fmt (:stdev f)) " median " (fmt (:median f))
                   " n " (:n f))
              (str "    wall ms per invocation  utsushi "
                   (fmt (:mean (g/summarize (get-in result [:utsushi :wall-ms-short]))))
                   " (" (:short-frames result) "f) / "
                   (fmt (:mean (g/summarize (get-in result [:utsushi :wall-ms-long]))))
                   " (" (:utsushi-long-frames result) "f)")
              (str "                            ffmpeg  "
                   (fmt (:mean (g/summarize (get-in result [:ffmpeg :wall-ms-short]))))
                   " (" (:short-frames result) "f) / "
                   (fmt (:mean (g/summarize (get-in result [:ffmpeg :wall-ms-long]))))
                   " (" (:ffmpeg-long-frames result) "f)")
              (str "    resolution              " (name (:resolution gate)))
              (str "    utsushi / ffmpeg        "
                   (if (:slowdown-factor gate)
                     (str (fmt (:slowdown-factor gate)) "x slower per frame"
                          "  (UNQUALIFIED ratio — see the gate below)")
                     "not derived: a slope that did not resolve must not be divided into anything"))
              (str "    smallest improvement this noise could pass: "
                   (fmt (* 100.0 (:minimum-detectable gate))) "%")]
             (reason-lines "    gate[utsushi as candidate]" (:utsushi-as-candidate gate))
             (reason-lines "    gate[ffmpeg  as candidate]" (:ffmpeg-as-candidate gate))
             [""]))))
      (map (fn [r v] {:result r :gate v}) results verdicts)))))

;; ── main ─────────────────────────────────────────────────────────────────

(defn- parse-args [args]
  (loop [a args m {:samples 5 :out nil :skip-kotoba-probe? false}]
    (if (empty? a)
      m
      (case (first a)
        "--samples" (recur (drop 2 a) (assoc m :samples (Long/parseLong (second a))))
        "--out" (recur (drop 2 a) (assoc m :out (second a)))
        "--skip-kotoba-probe" (recur (rest a) (assoc m :skip-kotoba-probe? true))
        (recur (rest a) m)))))

(def fixtures-manifest "bench/ffmpeg-comparison/fixtures.edn")
(def fixtures-dir "bench/ffmpeg-comparison/fixtures")

(defn fixtures
  "The fixture list from `fixtures.edn`, each paired with its file.

  Returns `[]` when the manifest or a file named in it is missing — which
  becomes a refusal, not an empty run that reads as clean."
  []
  (let [manifest (io/file fixtures-manifest)]
    (if-not (.isFile manifest)
      []
      (vec (keep (fn [{:keys [file] :as m}]
                   (let [f (io/file fixtures-dir file)]
                     (when (.isFile f) (assoc m :fixture-file f))))
                 (edn/read-string (slurp manifest)))))))

(defn probe-engines
  "Availability of every engine in the roster, measured.

  `ffmpeg` and `utsushi-jvm` are probed by trying them. The kotoba
  backends are probed by running `amu` — the stage each stops at is a
  measurement, and `--skip-kotoba-probe` records `:not-probed`, which is
  deliberately a different value from `:unavailable`: a probe that did not
  run must not report what a probe that ran would have."
  [{:keys [ffmpeg-provenance skip-kotoba-probe? kotoba-timeout-ms]}]
  (into
   [{:engine/id :ffmpeg
     :engine/role :reference
     :status (if (= :measured (:status ffmpeg-provenance)) :measured :unavailable)
     :stage (if (= :measured (:status ffmpeg-provenance)) :execute :cli)
     :version (:version ffmpeg-provenance)
     :build (:build ffmpeg-provenance)
     :reason (:reason ffmpeg-provenance)}
    {:engine/id :utsushi-jvm
     :engine/role :incumbent-oracle
     :status :measured
     :stage :execute
     :version (str "utsushi.pipeline.mp4-h264/decode-h264-frames over "
                   "org-iso-h264 h264.decode/decode-gop, full Clojure on "
                   (System/getProperty "java.vm.name") " "
                   (System/getProperty "java.version"))
     :note "incumbent, not the destination — ADR-2607198300 wants no JVM at run time"}]
   (mapv (fn [{:keys [engine/target] :as e}]
           (merge e
                  (if skip-kotoba-probe?
                    {:status :not-probed
                     :reason "--skip-kotoba-probe was passed; this is NOT a measurement of availability"}
                    (engines/probe-kotoba-backend {:target target
                                                   :timeout-ms kotoba-timeout-ms}))))
         engines/kotoba-backends)))

(defn run
  "The whole comparison as data. Pure enough to be called from a test."
  [{:keys [samples skip-kotoba-probe? kotoba-timeout-ms]
    :or {kotoba-timeout-ms 300000}}]
  (let [machine (host/descriptor)
        bin (ffmpeg-binary)
        ff (ffmpeg-provenance bin)
        roster (probe-engines {:ffmpeg-provenance ff
                               :skip-kotoba-probe? skip-kotoba-probe?
                               :kotoba-timeout-ms kotoba-timeout-ms})
        measurable (filterv #(= :measured (:status %)) roster)
        specs (fixtures)
        base {:host-summary (host/summary machine)
              :machine machine
              :ffmpeg ff
              :engines roster
              :policy g/default-policy}]
    (cond
      (not= :measured (:status ff))
      (merge base
             {:verdict :refused
              :reason :ffmpeg-unavailable
              :evidence {:engines-in-roster (count roster)
                         :engines-measurable (count measurable)
                         :fixtures-attempted (count specs) :fixtures-compared 0
                         :samples-taken 0 :samples-per-arm samples
                         :mismatches 0 :errors 0}
              :results [] :verdicts []
              :note "ffmpeg is the reference; without it there is no comparison to pass or refuse"})

      (empty? specs)
      (merge base
             {:verdict :refused
              :reason :no-fixtures
              :evidence {:engines-in-roster (count roster)
                         :engines-measurable (count measurable)
                         :fixtures-attempted 0 :fixtures-compared 0
                         :samples-taken 0 :samples-per-arm samples
                         :mismatches 0 :errors 0}
              :results [] :verdicts []
              :note (str "no readable fixture named by " fixtures-manifest
                         "; refusing to report a clean run over nothing")})

      :else
      (let [results (mapv #(compare-fixture (assoc % :ffmpeg-bin bin :samples samples)) specs)
            verdicts (mapv #(when (= :compared (:status %)) (qualify-fixture % machine)) results)
            compared (count (filter #(= :compared (:status %)) results))
            mismatches (count (filter #(= :mismatch (:status %)) results))
            errors (count (filter #(= :error (:status %)) results))
            taken (reduce + 0 (map #(if (= :compared (:status %)) (* 4 samples) 0) results))]
        (merge base
               {:verdict (cond (pos? mismatches) :mismatch
                               (zero? compared) :refused
                               :else :measured)
                :reason (cond (pos? mismatches) :engines-disagree
                              (zero? compared) :nothing-compared)
                :evidence {:engines-in-roster (count roster)
                           :engines-measurable (count measurable)
                           :fixtures-attempted (count specs)
                           :fixtures-compared compared
                           :samples-taken taken
                           :samples-per-arm samples
                           :mismatches mismatches
                           :errors errors}
                :results results
                :verdicts verdicts})))))

(defn -main [& args]
  (let [{:keys [samples out skip-kotoba-probe?]} (parse-args args)
        report (run {:samples samples :skip-kotoba-probe? skip-kotoba-probe?})]
    (doseq [l (render-lines report)] (println l))
    (when out
      (io/make-parents out)
      (with-open [w (io/writer out)] (pp/pprint report w))
      (println (str "report written to " out)))
    (flush)
    (System/exit (case (:verdict report)
                   :measured exit-measured
                   :mismatch exit-mismatch
                   exit-could-not-measure))))
