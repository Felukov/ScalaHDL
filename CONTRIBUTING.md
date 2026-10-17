# Contributing

ScalaHDL is a Scala 3 HDL targeting Verilog-2001. Read
[`hdl-spec.md`](hdl-spec.md) first: it is the requirements contract, and every
change should be checkable against a requirement in it.

## Build, test, emit, lint

```sh
sbt compile
sbt test
./emit.sh scalahdl.examples.Counter        # emit a top module to generated/verilog
./tools/lib-sim.sh       # the library's testbenches through Verilator
```

`./emit.sh <fully-qualified-class> [output-dir]` emits one Verilog file per
module to `generated/verilog`, or prints the diagnostic and exits non-zero when
the design does not validate. `./tools/lib-sim.sh` needs Verilator on `PATH` and
exits 2 without it; the Verilator-dependent suites skip in that case, so
`sbt test` is green either way.

## Emission tests

ScalaHDL emits Verilog, so the tests pin the exact text it emits. Each test
emits a small `core` design and compares the result, byte for byte, with a
Verilog file kept under `core/src/test/resources/scalahdl/golden/`. A person
writes those files; no test writes them back. When the emitted text changes,
decide whether the emitter or the kept file is wrong, then edit the file by
hand — that edit is the review, because the emission format is contractual. See
`scalahdl.golden.GoldenCorpus`.
