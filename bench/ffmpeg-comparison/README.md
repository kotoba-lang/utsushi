# `bench/ffmpeg-comparison` — utsushi against ffmpeg, and against where utsushi is going

```sh
kbb -M:bench                        # 5 samples per arm, probes the kotoba toolchain
kbb -M:bench --samples 9 --warmups 6 --out /tmp/report.edn
kbb -M:bench --skip-kotoba-probe    # fast; records the kotoba arms as :not-probed
kbb -M:bench-fixtures               # regenerate the fixtures (needs ffmpeg on PATH)
kbb -M:bench-profile gop320x240.mp4 120   # where utsushi's per-frame time goes
```

Exit codes: **0** measured, **1** engines produced different pixels, **3** could not
measure. Three values because there are three outcomes, and *could not measure*
returning the same value as *measured, clean* is the exact failure this harness
exists to prevent.

Modelled on [`kotoba-lang/amu`'s `bench/runtime-comparison`](https://github.com/kotoba-lang/amu)
and its ADR 0226, *cross-language runtime evidence is workload-bound*. The
statistics are [`kotoba-lang/perfgate`](https://github.com/kotoba-lang/perfgate)'s;
nothing here computes a mean and calls it a result.

## What this bench can and cannot establish (measured 2026-08-30)

**It can.** On both hosts measured, all six timed arms resolved a per-frame cost,
no fixture's engines disagreed on a single pixel, and `perfgate` sealed claims:

| host | load1 | arms resolved | perfgate-qualified | claims sealed |
|---|---:|---:|---:|---:|
| `MacBookPro18,4` / M1 Max (this workstation) | ~200 | 6 of 6 | 1 of 3 | 1 |
| `benjamin`, `Mac16,10` / M4 (murakumo fleet) | 1.8 | 6 of 6 | 2 of 3 | 2 |

**utsushi is two to three orders of magnitude slower than ffmpeg per frame of
decode CPU on this workload, and the ratio is not one number — it grows with
picture size.** Distribution-free envelopes, computed from the observed samples
with no assumption about the noise's distribution:

| fixture | utsushi ms/frame CPU | ffmpeg ms/frame CPU | ratio | envelope | decades |
|---|---:|---:|---:|---|---|
| gop32x3 (32×32) | 0.596 | 0.0152 | 39.3× | 30.9× .. 52.4× | 1.49 – 1.72 |
| gop160x128 | 8.715 | 0.0333 | 261.7× | 257.0× .. 265.5× | 2.41 – 2.42 |
| gop320x240 | 32.295 | 0.0780 | 413.8× | 335.5× .. 464.0× | 2.53 – 2.67 |

(`benjamin`, load1 1.8, 5 samples per arm, 4 warm-ups. The workstation's numbers
are 30–50× larger in absolute terms and give the same three ratios to within
their envelopes: 43.1×, 311.2×, 475.9×.)

**It cannot** resolve the 32×32 fixture's utsushi arm anywhere. Its relative
standard deviation was 0.196 on a machine at load 200, 0.176 at five times the
stretch, and **0.195 on a machine at load 1.8** — see *the noise that is not
contention* below. That arm is reported, printed, and refused.

**It cannot** speak for general video. See *what these numbers are not*.

## The engine roster is the point

ADR 0226 defines a comparison as a set of **adapters**, with unavailable ones
recorded by name and reason (`skippedEngines`) rather than omitted. This bench
keeps that discipline, and the reason is not tidiness:

| engine | role | status |
|---|---|---|
| `ffmpeg` | reference | measured |
| `utsushi-jvm` | **incumbent oracle** | measured |
| `kotoba-wasm32` | target backend | unavailable — stage recorded |
| `kotoba-native-aarch64` | target backend | unavailable — stage recorded |
| `kotoba-script-js` | target backend | unavailable — stage recorded |

A two-engine table would read as *utsushi (JVM) vs ffmpeg*, and therefore as if
closing that gap were the whole job. It is not.
[ADR-2607198300](https://github.com/com-junkawasaki/root) says the destination is
an artifact that needs no JVM, Node or Rust at run time. The JVM arm is the
**oracle** — it says which pixels are correct — and the incumbent. It is not the
finish line, and a report that cannot show the difference is a report that hides it.

### A `.cljc` extension does not mean amu can compile the file

amu's `.cljc` is the **Kotoba guest grammar** wearing a `.cljc` extension. It is a
much narrower language than the full Clojure `utsushi` and `org-iso-h264` are
written in. So the kotoba arms' unavailability is not one fact but a ladder, and
this bench measures which rung each one stops on by *running amu*, not by asserting:

| stage | meaning |
|---|---|
| `:cli` | the compiler CLI was not found at all |
| `:source-read` | amu's reader rejected the source — it is not guest grammar |
| `:check` | admitted by `amu check`; effects and exports resolved |
| `:compile` | a backend accepted or rejected the admitted program |
| `:execute` | an artifact ran and produced pixels |

Measured 2026-08-29, the four rungs were four different distances: `decode.cljc`
stopped at `:source-read` with exit 65, `expgolomb.kotoba` passed `check` with
exit 0 and then stopped at `:compile` — differently per backend (aarch64 exit 70
`:kotoba/target-rejected`, wasm32 exit 70 `:kotoba/internal-error`).

**Re-measured 2026-08-30 against amu `1ac1241` (its current main), the rungs are
unchanged.** An earlier run of this probe reported both files failing `check`
with `:kotoba/invalid-data "input could not be read"` and concluded the two rungs
had become indistinguishable. That reading was wrong: `invalid-data` on a read is
a *file not found*, not a grammar refusal, and the probe was resolving its source
paths from the wrong directory. A path error was recorded as a language-level
rejection — the pessimistic direction, but a mistake either way.

```
amu check   h264/decode.cljc      -> exit 65  :kotoba/source-read-failed
amu check   h264/expgolomb.kotoba -> exit 0   admitted, effects #{}, 4 exports
amu check   h264/rbsp.kotoba      -> exit 0   admitted, effects #{}, 1 export
amu compile h264/expgolomb.kotoba --target aarch64 -> exit 70 :kotoba/target-rejected
amu compile h264/expgolomb.kotoba --target wasm32  -> exit 70 :kotoba/internal-error
```

The rungs remain four different distances, and the guest-grammar files still
clear `check` and stop at `:compile`, differently per backend. **Pass an absolute
path when probing**, and treat a read error as a probe defect rather than as
evidence about the language.

Note also that `orgs/kotoba-lang/amu` as checked out by west can be far behind
its own main — it was 133 commits behind when this was written — so a probe run
against it measures a different compiler from the one the pin names. Resolve
`bin/amu` from a checkout you have just synced, and record which SHA answered.

**The decoder itself still does not reach the compiler**: of the H.264 namespaces
in `org-iso-h264/src/h264`, only three (`expgolomb`, `rbsp`, `sps` — none of them
the decoder) have a guest-grammar twin.

## Same observable result, or no comparison

Before either engine is timed, both decode the fixture and their `yuv420p` bytes
must be **identical**. A fixture whose engines disagree is reported as a
`:mismatch` and nothing about it is timed: a speed ratio between two different
answers is not a speed ratio. All three fixtures passed on both hosts with zero
mismatching pixels.

ffmpeg's much longer stretched stream is not decoded by utsushi — that would take
hours per fixture — so it is checked a different way: N copies of a self-contained
GOP must decode to the short decode repeated N times, and ffmpeg's own long output
is asserted, byte for byte, to be exactly that.

That check is **streamed** (`repetition-of?`, a cyclic index into the short
output, O(1) memory). The earlier version built the expected repetition as a
Clojure vector of boxed `Integer`s and compared with `=`. At the stretches this
bench now uses, ffmpeg's long yuv output is ~100 MB, which that version cannot
hold. A precondition that cannot be afforded at the size that matters is a
precondition that stops covering the arm the numbers come from.

## The metric is CPU time, and that is the whole method fix

An earlier version timed **wall clock**. Measured 2026-08-30 on this workstation,
decoding the 3-frame 320×240 fixture:

```
ffmpeg    wall 2.07 – 2.88 s      CPU (utime+stime) 0.080 s, five runs identical
utsushi   wall 8.8 – 18.7 s       CPU 0.539 – 0.626 s
```

Wall time is thirty times CPU time and moves by a factor of two between
consecutive runs. On a machine running this many concurrent agents a wall clock
measures the scheduler's queue, and the decoder's cost is a small term inside it.
That is not a fixable quantity of noise — it is a different quantity.

So both engines are now timed in CPU:

- **ffmpeg**: `utime + stime` from its own `-benchmark`, which is a `getrusage`
  delta taken around the transcode loop, so process startup, dynamic linking and
  init are outside it *by construction*. `-threads 1` so the figure is one
  thread's work. Measured: 0.002 s where the whole process burns 0.08 s of CPU
  and 2.5 s of wall.
- **utsushi**: `ThreadMXBean/getCurrentThreadCpuTime` on the decoding thread.
  The whole-JVM figure is reported beside it; the difference is JIT and GC done
  off-thread (~12–18%). The thread figure is the one gated on because the process
  figure carries the compiler threads' noise — relative stdev 0.59 against 0.20
  on the 32×32 slope. That choice **undercounts utsushi, which is the slow arm**,
  so every *at least N times slower* statement derived from it stays true.

**`perfgate`'s policy is untouched.** Its `:max-relative-stdev` is still 0.10 and
its `:min-improvement` still 0.05. What changed is which quantity is handed to it.
Weakening the gate to manufacture a green would have been the larger defect.

Effect, measured on the same fixtures and the same host, as slope relative stdev:

| fixture | arm | wall | CPU |
|---|---|---:|---:|
| gop32x3 | utsushi | 0.260 | 0.117 |
| gop32x3 | ffmpeg | 0.561 | 0.146 |
| gop160x128 | utsushi | 0.345 | **0.054** |
| gop160x128 | ffmpeg | 0.269 | **0.018** |
| gop320x240 | utsushi | 0.244 | 0.186 |
| gop320x240 | ffmpeg | 0.197 | **0.051** |

## A negative per-frame cost is rejected, not printed

Each fixture is decoded at two frame counts and the per-frame cost is the
**slope**, at which the invocation's fixed cost cancels. The differenced quantity
a slope computes has no sign constraint, but a per-frame decode cost does: it is
strictly positive.

Measured 2026-08-29, the 320×240 ffmpeg wall slope came out **mean +0.085 ms/frame,
median −0.474, sd 2.81**. The harness tested only whether the mean was positive —
it was — and printed `41550x`. That number is not a slow result or a fast one; it
is the ratio of a real quantity to an invalid one.

`resolve-slope` now refuses it. Three conditions, each reported by name, all of
which must hold:

| reason | what it catches |
|---|---|
| `:physically-impossible-sample` | any sample ≤ 0 — one is enough, because the method produced a value that cannot exist |
| `:median-non-positive` | the bulk of the distribution is not positive, however the mean came out |
| `:not-separated-from-zero` | `mean − 2·stderr ≤ 0`: the positive mean is inside its own sampling error |

When either arm fails, **no ratio is derived at all** — not the point ratio, not
the envelope. The report prints `not derived` rather than a number with a caveat
attached, and `arms resolved N of M` appears on the evidence line so a refusal
cannot be silent. `refusal_test.clj` pins the 2026-08-29 sample set; reverting
`resolve-slope` to the mean-only rule turns five of its assertions red.

## The stretch factors are measured, not guessed

Each engine's stretch (`fixtures.edn`) was chosen by measuring the relative
standard deviation of the resulting slope and taking the value that measured
best. ffmpeg's went from 200/100/40 to 2000/1000/300: its CPU-timed fixed cost is
demux setup plus a 1 ms print resolution, and 300–500 ms of decode CPU on the
long arm makes both a fraction of a percent. The old values were sized against
wall time, where the fixed cost is process startup at 0.5–1.8 s with a standard
deviation of the same order — no stretch this bench could build would have
resolved a 10 ms decode out of that. Every measured value is tabulated in
`fixtures.edn` with the tolerance it was compared against.

## The noise that is not contention

`load1` is read before and after every engine's samples and printed next to the
numbers. amu ADR 0281 (2026-08-29) measured that **no host in this fleet produces
even one `load1` sample at or below 1.0** — a property of macOS `loadavg` on those
machines, not of contention — so "benchmarked on a quiet machine" is not a claim
anything here can make.

It does not follow that no *quieter* machine exists, so this was probed rather
than assumed. At 2026-08-30T16:20Z all seven fleet hosts (benjamin, dan, asher,
levi, judah, joseph, simeon) were unreachable over ssh; at 16:48Z all seven
answered, at `load1` **1.32 – 2.34** against this workstation's 370–450. The
earlier reading was a transient tailnet relay condition and **is not a property of
the fleet** — recording it as one would have made "no quieter host is available"
into a finding it never was.

The full comparison was then run on `benjamin` (`Mac16,10`, M4, 10 cores, load1
1.8, same ffmpeg 8.1.1 and same OpenJDK 26.0.1). It qualified **two** of the three
comparisons and sealed two claims, against one on the workstation. Wall time is
also usable there (slope relative stdev 0.010–0.184) — but CPU time is what makes
the workstation usable at all, and it is tighter on both.

**The 32×32 utsushi arm does not resolve on either host.** Its slope relative
stdev was 0.196 (workstation, stretch 20), 0.176 (workstation, stretch 100 —
five times the work) and 0.195 (benjamin at load 1.8). That is the signature of
**proportional** noise: at sub-millisecond CPU per 32×32 frame, JIT and GC
variance is a fixed fraction of the work, so a longer stream scales the noise with
the signal. More stretch cannot fix it and neither can more samples —
`perfgate`'s `:too-noisy` test is on the samples' own relative spread, which does
not shrink with n. That arm's number is printed and marked refused. It is the one
place in this bench where the honest answer is that the quantity is not resolvable
here, and it is not resolvable for a reason that has nothing to do with the
machine being busy.

## Where utsushi's time actually goes

`kbb -M:bench-profile` (a `.cljc` whose every form is `#?(:clj …)`, because JFR
is a JVM facility and the arm it profiles is the JVM incumbent) runs the same decode under a JFR execution-sample
recording and attributes samples two ways: by leaf frame, and by the deepest frame
inside `h264.*` / `utsushi.*` / `isobmff.*`. It times nothing and gates nothing,
and it is deliberately not run inside the comparison — a profiler attached to a
timed interval measures the profiler. Below 200 samples it says so and refuses to
be quoted.

Measured on `benjamin`, 120 decodes of `gop320x240.mp4`, **1732 samples**:

```
BY LEAF FRAME                          BY DEEPEST DECODER FRAME
 25.5%  PersistentVector/doAssoc        42.3%  h264.decode/decode-picture$assemble …
 11.5%  RT/get                          15.6%  h264.decode/add-residual-16x16 …
  9.1%  clojure.core/get-in             10.1%  h264.decode/add-residual-8x8 …
  8.1%  RT/seqFrom                       7.0%  h264.decode/add-residual-16x16 …
  6.5%  clojure.core/chunk-first         3.9%  h264.decode/add-residual-8x8 …
  5.5%  Util/isInteger                   2.4%  h264.transform/inverse-4x4
  4.0%  TransientVector/conj             1.0%  h264.transform/luma-dc-hadamard
  3.6%  RT/assoc
```

The gap is **not** entropy decoding and **not** the transform. Exp-Golomb and
CAVLC do not appear in the top twenty at all; the inverse 4×4 transform and the
luma DC Hadamard together are 3.4%. About 78% of leaf samples are in Clojure's
persistent-collection machinery — `doAssoc`, `get-in`, `RT/get`, seq traversal,
boxing — and about 85% of decoder-attributed samples are in the two places that
write pixels one at a time into immutable vectors: picture assembly (42%) and
residual addition (43%).

That is the finding, and this task changed nothing on the strength of it.

## Where the gap comes from

This bench sizes the gap; it does not attribute it.
[`bench/decode-cost-attribution`](../decode-cost-attribution/README.md) does,
by running the same operation on the same data in two containers and timing
both arms interleaved. Measured on benjamin the same day: the data
representation is worth **~2.9x** of the ratio, plane assembly is a pass
ffmpeg does not perform at all, and no carrier in amu's value model holds one
frame plane — so moving the arithmetic kernels into a Kotoba guest today would
move the cheap part.

## What these numbers are not

- **Not a general video decode ranking.** The fixtures are flat, DC-heavy,
  Intra_16x16 baseline content, because that is the only real libx264 output
  `org-iso-h264` decodes at all (it has no Intra_4x4 path, and libx264 cannot be
  told to stop emitting Intra_4x4 — see `utsushi.pipeline.mp4-h264`'s docstring).
- **Not a like-for-like harness cost.** ffmpeg is timed decoding to `-f null -`:
  it decodes and discards. utsushi builds Clojure vectors of pixels. That
  difference favours ffmpeg and is not corrected for.
- **Not comparable across the two hosts.** M1 Max and M4 are different machines
  and `perfgate` refuses a cross-machine comparison by fingerprint
  (`:machine-mismatch`). The two tables above are two measurements, not one.
- **Not the answer for `utsushi as candidate`.** That direction is refused on
  every fixture, and should be: the improvement is large and negative. The
  `ffmpeg as candidate` direction is the control — a gate that only ever refuses
  is indistinguishable from a gate that is broken.

## Regenerating the fixtures

```sh
kbb -M:bench-fixtures     # needs ffmpeg on PATH
```

The encoder settings are load-bearing and are documented in
`utsushi.bench.generate-fixtures` and in
`test/utsushi/pipeline/mp4_h264_gop_test.cljk`. (This used to be documented as
`kbb -M -m utsushi.bench.generate-fixtures`, which does not work: the class
path comes from an alias, and `-M:bench -m other.ns` does not override the
alias's `:main-opts` — the extra args are appended as *arguments* to the alias's
main, so that invocation silently runs the benchmark instead.)
