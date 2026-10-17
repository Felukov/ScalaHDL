# Examples

Designs built against ScalaHDL, and the reference for its advanced features.
This index names every example and the feature it shows, so a reader who
arrives at this directory finds the design that demonstrates a feature.

Every source below lives in
[`src/main/scala/scalahdl/examples/`](src/main/scala/scalahdl/examples/). The
examples are compiled and validated as part of `sbt test`, so each is a
design the library accepts, not a sketch.

These are **demonstrations meant to be read and edited**: a reader learns a
feature from them and a teaching edit should be free. Their tests therefore
assert only what an example *promises* — that it emits one file per module plus a
manifest, that the output is reproducible and carries the header, that Verilator
lints it clean, and that it behaves (the FSM equivalence oracle, the async-FIFO
testbench, the expression oracle). They do **not** pin the emitted bytes: that
belongs to the `core` emission tests ([`CoreGoldenSuite`](../core/src/test/scala/scalahdl/golden/CoreGoldenSuite.scala)),
so editing an example cannot fail the emitter's contract.

## The examples

| File | Feature it shows |
|---|---|
| [`Counter.scala`](src/main/scala/scalahdl/examples/Counter.scala) | The minimal end-to-end slice: explicit clock and reset ports, a register, a conditional increment |
| [`CounterGen.scala`](src/main/scala/scalahdl/examples/CounterGen.scala) | Name capture from a `val` inside a helper `def` and one inside a loop body — the two places field reflection cannot reach |
| [`NestedModules.scala`](src/main/scala/scalahdl/examples/NestedModules.scala) | A three-level hierarchy emitted child-first and deterministically; a parameter that contributes to the module name |
| [`ParameterizedModules.scala`](src/main/scala/scalahdl/examples/ParameterizedModules.scala) | Module-name derivation from constructor parameters, and the explicit name argument that replaces it |
| [`RegisterFile.scala`](src/main/scala/scalahdl/examples/RegisterFile.scala) | Aggregates: a register file exercising the bundle and `Vec` surface |
| [`RegistersMemory.scala`](src/main/scala/scalahdl/examples/RegistersMemory.scala) | A register-backed memory with a zero-cycle asynchronous read and explicit `ReadFirst` collision behaviour |
| [`LutRamMemory.scala`](src/main/scala/scalahdl/examples/LutRamMemory.scala) | A distributed LUT RAM with a zero-cycle asynchronous read |
| [`BlockRamMemory.scala`](src/main/scala/scalahdl/examples/BlockRamMemory.scala) | A block RAM with its required one-cycle synchronous read |
| [`DslExample.scala`](src/main/scala/scalahdl/examples/DslExample.scala) | Width discipline in a pipelined multiply-accumulate, in the shape of a hard DSP slice: every widening written, each a different rule |
| [`FetchFsm.scala`](src/main/scala/scalahdl/examples/FetchFsm.scala) | The fetch controller as one `switch`, as a state machine, as the `when`-chain oracle, and with arms produced by a Scala iterator |
| [`WidthMismatches.scala`](src/main/scala/scalahdl/examples/WidthMismatches.scala) | The diagnostic gallery: the ways two operands can disagree and what each is reported as. Run from the repository root |
| [`SafeClockCrossing.scala`](src/main/scala/scalahdl/examples/SafeClockCrossing.scala) | A complete, safe two-clock design through the crossing primitives |
| [`AsyncFifo.scala`](src/main/scala/scalahdl/examples/AsyncFifo.scala) | A lossless dual-clock FIFO with ordinary valid/ready endpoints — the validation exercise |
| [`CpuBridge.scala`](src/main/scala/scalahdl/examples/CpuBridge.scala) | A request/response bus as bundles carried across an instance boundary: one blueprint used at both ends of the hierarchy |
| [`CpuAddressMap.scala`](src/main/scala/scalahdl/examples/CpuAddressMap.scala) | An instance array in front of the bridge, decoded by an address map with `parallelCase` |
| [`CpuMaps.scala`](src/main/scala/scalahdl/examples/CpuMaps.scala) | A generator is a function: the same two decode `def`s reused by two designs that share no supertype or parameter |
| [`CpuUtils.scala`](src/main/scala/scalahdl/examples/CpuUtils.scala) | Design-level helpers over a bus, and the difference between a helper that **declares** a `Vec` and one that drives the caller's |

[`Emit.scala`](src/main/scala/scalahdl/examples/Emit.scala) is not an example:
it is the command-line generator that `./emit.sh` runs to elaborate any
no-argument top module above and write its Verilog.

## Components a design imports

[`core/src/main/scala/scalahdl/lib/`](../core/src/main/scala/scalahdl/lib/) holds
the components a design **imports into a design**. They are not examples: a
reader uses them and does not edit them, so they are not in the table above.
The directory's third file, `ResetShape.scala`, is their shared reset-naming
support and is `private[lib]`, not a component.

| Component | What it is |
|---|---|
| [`Skid.scala`](../core/src/main/scala/scalahdl/lib/Skid.scala) | A register slice (R30): two valid/ready stages, one beat of slack, and no combinational path from `m_ready` to `s_ready`. It states its own invariant in a `simOnly` block (R27) |
| [`SyncFifo.scala`](../core/src/main/scala/scalahdl/lib/SyncFifo.scala) | A same-domain FIFO of `depth` words (R32) — the pair of the `AsyncFifo` example |

Each carries a SystemVerilog testbench under
[`core/src/test/verilog/`](../core/src/test/verilog/), which
[`./tools/lib-sim.sh`](../tools/lib-sim.sh) runs against its emitted Verilog.

## References that live among the tests

Some references are not under `examples/`; their tests are the example:

- Aggregates — [`BundleSmokeSuite`](../core/src/test/scala/scalahdl/aggregates/BundleSmokeSuite.scala),
  [`PackingSuite`](../core/src/test/scala/scalahdl/aggregates/PackingSuite.scala),
  [`VecWriteSuite`](../core/src/test/scala/scalahdl/aggregates/VecWriteSuite.scala)
- Black-box integration — [`BlackBoxSuite`](../core/src/test/scala/scalahdl/blackbox/BlackBoxSuite.scala)
- Simulation-only checks — [`SimOnlySuite`](../core/src/test/scala/scalahdl/simonly/SimOnlySuite.scala)
  is what the construct promises, on a design that declares one; the `Skid`
  component above is where a design uses it in earnest

## Running one

From the repository root, emit any no-argument top in this directory:

```sh
./emit.sh scalahdl.examples.RegisterFile
```

The generated Verilog and reports land in `generated/verilog/`. The example
that is *deliberately wrong* — the width-diagnostic gallery — is run directly,
and prints a diagnostic per case:

```sh
sbt "examples/runMain scalahdl.examples.WidthMismatches"
```
