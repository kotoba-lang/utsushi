# `bench/decode-cost-attribution` — which part of the 497x is the data representation

```sh
clojure -M:bench-attribution                        # 9 samples x 20 reps, gop320x240
clojure -M:bench-attribution --samples 11 --reps 50 --out /tmp/report.edn
```

Exit codes: **0** measured and every arm qualified, **1** the arms computed
different pixels, **3** could not measure, **4** measured but `perfgate`
refused. Four values because there are four outcomes. *Could not run* must not
return what *ran clean* returns, and *ran but was refused* must not return
either.

`bench/ffmpeg-comparison` answers **how much** slower utsushi is than ffmpeg.
`utsushi.bench.profile` answers **where the samples land**. Neither answers
**why**, and this one exists because a leaf-sample profile is not an
attribution: `clojure.lang.PersistentVector.doAssoc` at the top of the leaf
table is equally consistent with *persistent vectors are the cost* and with
*the algorithm performs far too many stores*. Those are different findings with
different consequences, and the difference is what a decision about a JVM-free
decoder turns on.

The discriminator is the cheapest one there is: run the **same operation on the
same data twice, changing only the container**, and time both arms interleaved
in one loop.

## What was measured

Host **benjamin**, `Mac16,10` / Apple M4, 2026-08-30.
`ThreadMXBean.getCurrentThreadCpuTime`, n=11, 50 repetitions per sample, all
six arms interleaved inside one loop so a load excursion moves all of them.
Fixture `gop320x240.mp4`, 300 macroblocks, 115,200 samples per frame (76,800
luma + 2 x 19,200 chroma). Statistics are
[`perfgate`](https://github.com/kotoba-lang/perfgate)'s at its default policy;
nothing here computes a mean and calls it a result.

**Two independent runs are reported, not one.** They were taken an hour apart
at `load1 1.71 -> 1.73` and `1.64 -> 1.70`, and they differ by 5-9% on every
arm. Presenting one would make the numbers look sharper than they are.

| arm | run A ms/frame | rel-sd | run B ms/frame | rel-sd |
|---|---:|---:|---:|---:|
| full decode (`pipeline/decode-h264-frames`) | **33.841** | 0.036 | **35.643** | 0.045 |
| residual addition — persistent (production vars) | **16.275** | 0.080 | **17.457** | 0.065 |
| residual addition — primitive arrays | **1.277** | 0.027 | **1.274** | 0.030 |
| plane assembly — persistent (transcription) | **7.046** | 0.012 | **7.634** | 0.011 |
| plane assembly — primitive arrays | **0.109** | 0.020 | **0.108** | 0.018 |
| `inverse-4x4`, shared by both residual arms | 0.705 | 0.032 | 0.725 | 0.027 |

| quantity | run A | run B |
|---|---:|---:|
| residual addition, persistent / primitive | **12.74x** | **13.70x** |
| plane assembly, persistent / primitive | **64.62x** | **70.73x** |
| residual addition, share of decode | 48.1% | 49.0% |
| plane assembly, share of decode | 20.8% | 21.4% |
| `inverse-4x4`, share of decode | 2.1% | 2.0% |
| everything else (unattributed here) | 31.1% | 29.6% |

Both ratios are **QUALIFIED** by `perfgate` in both runs (run A: gap 14.998 >
summed-stdev 1.342, and 6.937 > 0.083). Both single-arm components
(`inverse-4x4`, full decode) pass its sample-count, spread and provenance tests
in both runs.

A first attempt at 20 reps produced the same ratios and was **refused** —
`:too-noisy`, residual-add's persistent arm at relative stdev 0.118 against the
policy's 0.10. Raising the repetitions per sample (more work averaged inside
each sample, not a wider tolerance) resolved it to 0.080 and 0.065. The policy
was not touched; the refusal is recorded here because a refusal is a result.

## The three answers

### (a) The representation is the dominant term, and ~65-71x is the clean number

**Plane assembly is nothing but data movement** — 115,200 loads and 115,200
stores, no arithmetic, no transform, no branching on data. Both arms traverse
in the same order and perform the same number of stores. The only difference is
that one writes `assoc` into a 76,800-element persistent vector and reads
`get-in` out of nested ones, while the other writes `aset` into an `int-array`
and reads `aget`. That is **64.6x and 70.7x**, qualified in both runs, on the
operation where nothing else can be responsible.

Per sample: **61-66 ns** persistent, **0.94-0.95 ns** primitive. For scale,
ffmpeg's *entire* frame decode is 0.65 ns per sample.

### (b) The algorithms as written are not the problem — but one whole pass is

Residual addition shows a smaller ratio, 12.7x and 13.7x, and the reason is
measurable rather than arguable: both of its arms call the **production**
`h264.transform/inverse-4x4`, which is itself persistent-vector code. That
shared cost is 0.71-0.73 ms/frame and sits inside both numbers, so it cancels
out of their difference and drags the ratio down. Subtracting it:

```
run A   persistent container work   16.275 - 0.705 = 15.571
        primitive  container work    1.277 - 0.705 =  0.573    ratio 27.2x   [derived]
run B   persistent container work   17.457 - 0.725 = 16.732
        primitive  container work    1.274 - 0.725 =  0.549    ratio 30.5x   [derived]
```

So the two operations bracket the container cost at roughly **27x to 71x**, and
the 12.7x headline is a floor rather than an estimate.

The genuinely algorithmic finding is elsewhere. **Plane assembly is a pass
ffmpeg does not perform at all.** ffmpeg reconstructs each macroblock directly
into the destination picture plane through a stride; there is no separate
scatter. That is not a claim about ffmpeg's source read from here — it follows
from this bench's own numbers: **the primitive-array assembly pass alone costs
0.108 ms/frame, and ffmpeg's entire frame decode costs 0.075 ms/frame.** A
decoder cannot contain a pass that costs 1.4x its whole runtime. The pass
exists in `h264.decode/decode-picture` because macroblock reconstruction
returns immutable per-macroblock grids that then have to be scattered
somewhere, and ~21% of decode CPU is the price of that shape.

### (c) The JVM is not separated out here, and is not the leading candidate

This bench does not isolate the runtime, and says so rather than implying it.
What it does establish is the size of what is left. Converting **only** these
two hot spots to primitive arrays, holding everything else fixed:

```
run A   rest of decode  33.841 - 16.275 - 7.046 = 10.520 ms
        + arrays                 1.277 + 0.109  = 11.906 ms/frame   speedup 2.84x
run B   rest of decode  35.643 - 17.457 - 7.634 = 10.552 ms
        + arrays                 1.274 + 0.108  = 11.935 ms/frame   speedup 2.99x
```

**~2.9x is arithmetic over four measured means from a single interleaved run**,
reproduced twice. Applied to the same day's comparison-bench ratio on the same
host (497.3x, below), the remaining gap would be **~167-175x** — *derived*, not
measured. So the representation is worth a large fraction of the *time* and a
small fraction of the *ratio*: two orders of magnitude survive it.

The remaining ~30% is bitstream parsing, CAVLC, intra prediction and chroma DC.
It is unattributed by this measurement and no number is offered for it. For
scale only, and clearly labelled an **estimate with a named assumption**: if
that 30% responded to the same container change by the *smaller* of the two
measured factors, the total would be ~2.2 ms/frame, ~28x ffmpeg. Nothing here
measures that, and it must not be quoted as if it did.

## Why the shares here differ from the JFR profile, and which to use

Run the same day on the same host, `clojure -M:bench-profile gop320x240.mp4 60`
took 981 execution samples (floor 200) and attributed, by deepest decoder
frame:

| operation | JFR profile | this bench (CPU time) |
|---|---:|---:|
| plane assembly | 42.1% | **20.8% / 21.4%** |
| residual addition | 43.5% | **48.1% / 49.0%** |
| inverse transforms | 3.3% | **2.1% / 2.0%** |
| both hot spots together | 85.6% | **68.9% / 70.4%** |

They agree on *which code* and disagree on *how much*, chiefly for assembly.
They are different instruments. The profile is a distribution over 1 ms stack
samples inside a real decode; this bench is a direct CPU-time measurement of the
same function called in a tight loop. **The tight loop gives the operation a
clean nursery it never gets inside the decoder**, where the heap is already full
of macroblock garbage — allocation-heavy persistent code runs faster under those
conditions than in situ. That makes this bench's persistent arms, if anything,
optimistic, and its ratios floors for a third reason.

The percentages used for arithmetic above are this bench's, because they are
ratios of quantities measured against each other inside one loop rather than
carried between instruments. The profile is used as a cross-check on which code,
not on how much.

## Anchors measured the same day on the same host

`clojure -M:bench --samples 7 --skip-kotoba-probe` on benjamin, 2026-08-30:

| fixture | utsushi CPU ms/frame | ffmpeg | ratio | envelope |
|---|---:|---:|---:|---|
| 32x32 | 0.665 (rel-sd 0.194, **refused**) | 0.016 | 41.9x | 29.6-55.3x |
| 160x128 | 9.200 | 0.033 | 278.1x | 270.1-285.9x |
| 320x240 | 37.143 | 0.075 | **497.3x** | 439.8-555.4x |

**The ratio itself moves between runs.** The previously recorded run on this
same host gave 413.8x at 320x240; today it gave 497.3x, and the two envelopes
overlap. Quote it as *several hundred x, rising with picture size*, not as a
constant. The 32x32 utsushi arm is refused for `:too-noisy` at every stretch
tried, as `fixtures.edn` records.

This bench's own full-decode arm reads 33.8-35.6 ms/frame against the comparison
bench's 37.1. They are different quantities: the comparison bench's is a
**slope** between a 3-frame and a 9-frame decode, so it is the marginal
(P-frame) cost with the fixed cost cancelled; this one is one 3-frame decode
divided by 3, so it averages one IDR with two P-frames and carries the demux.
Both are reported; neither is corrected into the other.

---

# What a Kotoba guest could execute, bounded

The measurement above is the input to a live architecture question.
ADR-2607198300 says the destination is an artifact needing no JVM, Node or Rust.
If bulk pixel data cannot enter a Kotoba guest, and the bulk pixel work is where
the time is, then moving the arithmetic kernels into Kotoba moves the cheap
part.

## The carriers, and what fits in them

Read at `kotoba-kir` `099c627`, `amu` `5a2d188`, `kotoba-wasm` `a739f37`,
`kotoba-script` `65da967`, `kotoba-native` `3dab370`. Every figure is a literal
constant at the cited line, not an inference.

| carrier | capacity | citation |
|---|---:|---|
| `:vector-i64` / `:vector-f64` | **16,384 elements** | `kotoba-kir/src/kotoba/kir/value.cljc:16` |
| native element arena, whole process | 65,536 words | `amu/tools/kexe_loader.c:59` |
| native vector handle table | 4,096 | `amu/tools/kexe_loader.c:58` |
| wasm32 typed scratch | 2 pages = 131,072 B = **16,384 i64** | `kotoba-wasm/src/kotoba/wasm/core.cljc:97-98` |
| `:bytes` | 65,536 B, and **opaque** — no guest op indexes a byte | `kotoba-kir/.../value.cljc:11` |
| `:document` | 256 nodes, so <= 255 i64 leaves | `kotoba-kir/.../value.cljc:61` |
| `:string` | 65,536 UTF-8 **bytes** | `kotoba-kir/.../value.cljc:8` |
| `[:list :i64]` through the public API | 63 items (ADT node limit 64 charges per node) | `kotoba-kir/.../value.cljc:39, 1868-1870` |
| `[:set T]` / `[:map K V]` / hetero vector / record | 32 / 31 / 32 / 32 | `kotoba-kir/.../value.cljc:41-44` |

**No carrier in the value model holds 76,800 integers on any backend.** The best
case is a `:string` of 65,536 single-byte code points — which would also
restrict the value range to 128 — and that is still 11,264 short of one luma
plane. A 320x240 luma plane at 76,800 samples exceeds the *entire* native
element arena (65,536), so it cannot be resident in any number of chunks
simultaneously; it needs 4.69 maximum vectors and the process has room for fewer
than one plane.

Worse for the native path specifically: **a vector cannot be a function
parameter at all**, and no `typed-cap-call` request/result pair carries one
(`kotoba-kir/src/kotoba/kir.cljc:751-773` and `:474-484`). The only bulk-out
path is a zero-argument exported non-entry function returning `:vector-i64` —
and amu ADR 0284 records that `kexe_loader.c` has no vector value for
`KEXE_RESULT_TYPE`, so that path verifies and then hands its caller a raw table
index. **On native today, bulk data cannot enter a guest in any form.**

## The fraction that could execute inside a guest

Against the measured shares, at each operation's natural granularity:

| operation | share | natural unit | fits a 16,384 carrier? | verdict |
|---|---:|---|---|---|
| plane assembly | ~21% | the frame plane, 76,800 | **no** — 4.7x the per-vector cap, 1.2x the whole native arena | **must stay in the host** |
| residual addition | ~49% | one macroblock: 256 pred + 256 coeff in, 256 out | yes, 64x under the cap | eligible |
| inverse transforms | ~2% | one 4x4 block: 16 in, 16 out | yes | eligible |
| bitstream / CAVLC / intra pred / chroma DC | ~30% | not attributed by this bench | unknown | **unmeasured** |

**At most ~51% of measured decode CPU has a granularity that fits the carrier.
~21% provably does not. ~30% is unattributed and no fraction is claimed for it.**

And eligible is not the same as worth doing. Costing the crossing for the one
big eligible operation, residual addition at one guest call per macroblock:

```
per macroblock  luma   256 pred in + 256 coefficients in + 256 out  =   768
                chroma 2 x (64 + 64 + 64)                           =   384
                                                        per MB       = 1,152
                                              x 300 macroblocks      = 345,600 element crossings / frame
```

amu ADR 0284 measured, on native, **6.920 ns per element touched** through
`vector-at`, and **3.678 ns for the identical loop performing no element access
at all** (against 0.329 ns for the same loop in C at `-O3`). Applying those:

| at | crossing cost / frame | vs. the whole op in host arrays (1.27 ms) | vs. ffmpeg's whole decode (0.075 ms) |
|---|---:|---:|---:|
| 6.920 ns/element (measured, with the host call) | **2.392 ms** | **1.88x more** | 31.9x |
| 3.678 ns/element (the loop alone, host call removed entirely) | **1.271 ms** | **1.00x — exactly the same** | 16.9x |

**Moving the pixels across the boundary costs more than doing the entire
operation in the host with primitive arrays** — and it still costs the same even
if the host context call were removed completely, which ADR 0284 measured as the
backend loop's own stack traffic rather than the call. That is the answer to
whether a per-block guest is viable today: it is not, and the reason is not the
capacity cap. These two rows are *derived* — this bench did not run a guest;
it multiplies its own crossing count by ADR 0284's measured per-element cost.

For completeness, the capacity-free bound: touching every sample of one frame
exactly once, at 6.920 ns, is 0.797 ms/frame — **10.6x ffmpeg's entire frame
decode**, before any arithmetic, with an unlimited carrier.

## Conclusion

**A bulk carrier is a prerequisite, and raising the element cap is not
sufficient to make one.**

- Capacity alone does not clear the bar. At the measured per-element cost, a
  guest with an *unlimited* `:vector-i64` still could not touch one frame's
  samples for less than 10x ffmpeg's whole decode.
- The carrier has to be **guest-addressable memory the guest indexes with its
  own loads and stores**, not a handle whose elements arrive one host call at a
  time. The wasm32 typed scratch is already exactly that shape — the guest does
  `i64.store` at `offset + index*8` into imported linear memory
  (`kotoba-wasm/src/kotoba/wasm/core.cljc:932-968`) — but it is a write-only
  staging buffer, sized at exactly the vector cap, used to *construct* a vector
  that is then handed over as a handle.
- Capacity still has to move, and the target is not one plane. A decoder's
  minimum working set is the current picture plus one reference picture: 230,400
  samples at 320x240, 3,110,400 at 1080p. The present cap of 16,384 is **7.1% of
  the smaller of those**.
- Until such a carrier exists, the honest scope for Kotoba in this decoder is
  the ~2% in the transforms — precisely the part the profile says is not where
  the time goes. **Moving the arithmetic kernels to Kotoba today would move the
  cheap part**, and this bench is the number that says so.

The measurement is symmetric about who has work to do. The decoder's own ~2.9x
is available immediately and needs nothing from the compiler stack; the
remaining ~170x needs a value model that does not exist yet.

## Recommended, and deliberately not done

Everything in this bench is diagnostic. The primitive-array arms live under
`bench/`, are not called by `utsushi.pipeline` or `org-iso-h264`, and are not a
second decoder. No production code was changed. The following are
recommendations with their expected sizes, each derived from the tables above:

1. **Reconstruct macroblocks directly into a strided plane; delete the
   `assemble` pass.** `h264.decode/decode-picture` currently builds immutable
   per-macroblock grids and scatters them. Expected: removes ~21% of decode CPU,
   **1.26x on its own**, and it is the one change that removes work rather than
   making work cheaper. It is a design change (the plane must be mutable during
   reconstruction), not an optimization, and it belongs to `org-iso-h264`, not
   here.
2. **Give `add-residual-16x16` / `add-residual-8x8` a primitive-array
   destination.** Measured 12.7-13.7x on that operation, **~1.8x on the whole
   decode**. Note that this bench's "primitive" arm still reads the residual out
   of `inverse-4x4`'s nested persistent vectors, so a version that also changed
   the transform's output would do better than 13.7x by an amount bounded below
   by the 27-30x derived container-only figure.
3. Both together: **~2.9x**, taking the ratio from ~497x to ~170x.
4. **For the bulk carrier being designed in the compiler stack: gate it on ns
   per element the *guest* touches with its own load/store, not on capacity.**
   ADR 0284's middle row is the finding to build against — removing the host
   context call moved 21.1x to 11.2x, so the call was not the dominant cost. A
   capacity increase that keeps per-element host access will not make pixel work
   viable at any cap.

## What these numbers are not

- **Not a general video decode attribution.** The fixture is flat, DC-heavy,
  Intra_16x16 baseline content, because it is the only real libx264 output
  `org-iso-h264` decodes at all. On that content `inverse-4x4` takes its DC-only
  fast path, which is why the transforms measure at ~2%; on general content they
  would be larger and the residual and assembly shares smaller.
- **The residual-add coefficient blocks are synthesized.** Every other input
  comes off a real decode — the predictor grids and the assembly inputs are the
  decoder's own output sliced back into per-macroblock grids, and the assembly
  transcription is checked against the decoder's plane before anything is timed.
  Coefficient blocks are internal to a macroblock decode and no public entry
  point returns them, so they are generated DC-only to match the fixture. That
  is the one input that is not real, and it is named rather than blended in.
- **The plane-assembly persistent arm is a transcription, not a call.**
  `assemble` is a local `fn` inside `decode-picture` with no var to deref. The
  residual arms call the production vars directly. The transcription is pinned
  by requiring it to reproduce the decoder's own plane exactly.
- **Not a claim about the JVM.** No arm here isolates the runtime from the
  library. The ~2.9x is what the two containers are worth; what the remaining
  ~170x is made of is not answered.
- **Not a guest measurement.** No Kotoba backend ran anything here. The bounds
  in the second half multiply this bench's own crossing counts by constants read
  out of the compiler stack's source and by amu ADR 0284's measurements. When a
  backend can run a decoder kernel, that will be a different measurement against
  a different runtime.
