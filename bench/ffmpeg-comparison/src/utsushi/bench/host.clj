(ns utsushi.bench.host
  "The machine the numbers were taken on, read off the machine.

  `perfgate` refuses a claim whose machine descriptor is `:assumed`
  (`:provenance-too-weak`), and it is right to: a benchmark that describes
  hardware nobody queried is describing a machine that may not exist. Every
  field here therefore comes from a `sysctl`/`uname` call whose exact text is
  recorded in `:machine/source`, so a reader can re-run the same probe.

  ## Load is part of the measurement, not context for it

  `load1` is captured before AND after every run and reported next to every
  number. amu ADR 0281 measured that no host in this fleet reaches
  `load1 <= 1.0`, so \"quiet host\" is not a condition anything here can
  satisfy or claim. The honest alternative is not to wait for quiet — it is
  to state the load the numbers were taken at and let the reader discount
  them."
  (:require [clojure.string :as str]
            [clojure.java.shell :as sh]
            [machine.core :as m]))

(defn- sysctl [k]
  (let [{:keys [exit out]} (sh/sh "sysctl" "-n" k)]
    (when (zero? exit) (str/trim out))))

(defn- sysctl-long [k]
  (when-let [v (sysctl k)]
    (try (Long/parseLong v) (catch Exception _ nil))))

(defn load1
  "One-minute load average, read from `sysctl -n vm.loadavg`, or nil if the
  probe did not run. nil is deliberately not 0.0 — a load nobody measured
  must not read like an idle machine."
  []
  (when-let [v (sysctl "vm.loadavg")]
    (let [parts (str/split (str/replace v #"[{}]" "") #"\s+")]
      (try (Double/parseDouble (second (remove str/blank? (cons "" parts))))
           (catch Exception _
             (try (Double/parseDouble (first (remove str/blank? parts)))
                  (catch Exception _ nil)))))))

(defn descriptor
  "A `machine.core` descriptor for this host with `:measured` provenance.

  Only sections whose numbers were actually read are present: `machine.core`
  answers nil for an absent section rather than substituting a default, which
  is the behaviour worth having here. If `sysctl` is unavailable the CPU
  section is omitted entirely rather than guessed, and provenance drops to
  `:assumed` so `perfgate` refuses the claim instead of accepting invented
  hardware."
  []
  (let [brand (sysctl "machdep.cpu.brand_string")
        model (sysctl "hw.model")
        cores (sysctl-long "hw.ncpu")
        perf (sysctl-long "hw.perflevel0.logicalcpu")
        eff (sysctl-long "hw.perflevel1.logicalcpu")
        page (sysctl-long "hw.pagesize")
        arch (let [{:keys [exit out]} (sh/sh "uname" "-m")]
               (when (zero? exit) (keyword (str/trim out))))
        measured? (boolean (and cores arch))
        clusters (when (and perf eff (pos? perf) (pos? eff))
                   [{:id :performance :cores perf}
                    {:id :efficiency :cores eff}])]
    (m/validate!
     (cond-> {:format m/format-id
              :machine/id (str (or model "unknown-model") "/" (or brand "unknown-cpu"))
              :machine/provenance (if measured? :measured :assumed)
              :machine/source
              (str "sysctl -n hw.model machdep.cpu.brand_string hw.ncpu "
                   "hw.perflevel0.logicalcpu hw.perflevel1.logicalcpu hw.pagesize; uname -m"
                   (when-not measured? " (probe failed; hardware NOT measured)"))}
       (and cores arch) (assoc :cpu (cond-> {:arch arch :cores cores}
                                      clusters (assoc :clusters clusters)))
       page (assoc :page {:base-bytes page :huge []})))))

(defn summary
  "The host facts a report should print, as data."
  [descriptor]
  {:machine-id (:machine/id descriptor)
   :provenance (:machine/provenance descriptor)
   :fingerprint (m/fingerprint descriptor)
   :cores (get-in descriptor [:cpu :cores])
   :clusters (get-in descriptor [:cpu :clusters])
   :source (:machine/source descriptor)})
