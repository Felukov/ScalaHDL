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

## Publishing

The public repository is a release transcript, not a copy of this one. Its
history holds one commit per release and nothing else: no private commit, hash,
or provisional subject appears in it. `tools/export-public.sh` writes that
commit from `HEAD`.

To publish a release:

1. Add a `## <version> — <date>` section at the top of [`CHANGELOG.md`](CHANGELOG.md)
   and commit it. The heading names the release; the lines under it become the
   commit message.
2. Run the exporter against the release checkout:

   ```sh
   ./tools/export-public.sh ../ScalaHDL-public \
       git@github.com:Felukov/ScalaHDL.git --push
   ```

The script refuses three cases, each naming what to fix: a top heading that
still says `Unreleased` or `SNAPSHOT`; a tree that changed while the heading
still names a published version; and a destination directory holding history
the script did not write. A run with nothing new adds no commit.

The tree it publishes is `git archive HEAD`, so build output (`target/`,
`generated/`, `.bloop/`, `.metals/`) cannot reach the public repository. The
paths listed in the script's `exclude` array are removed as well; that array is
the only written record of the public/private split, so a new internal path is
published unless it is added there.

## Emission tests

ScalaHDL emits Verilog, so the tests pin the exact text it emits. Each test
emits a small `core` design and compares the result, byte for byte, with a
Verilog file kept under `core/src/test/resources/scalahdl/golden/`. A person
writes those files; no test writes them back. When the emitted text changes,
decide whether the emitter or the kept file is wrong, then edit the file by
hand — that edit is the review, because the emission format is contractual. See
`scalahdl.golden.GoldenCorpus`.
