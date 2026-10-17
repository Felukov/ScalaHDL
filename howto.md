# ScalaHDL quick start

ScalaHDL is intended to be used as a **library from a separate design project**.
Do not copy the ScalaHDL sources into each hardware project: that couples the
design to one library checkout and makes upgrades difficult. Depend on the
library by adding it to your build as an sbt project/source dependency.

This guide creates a small standalone counter project and emits Verilog-2001.

## Prerequisites

- **JDK 17 (LTS) or newer.** This is the supported minimum (see the README);
  sbt and Scala 3.3 both run on it.
- [sbt](https://www.scala-sbt.org/) (the repository currently uses sbt 1.10.7)
- ScalaHDL checked out locally
- Optional: [Verilator](https://www.veripool.org/verilator/) for linting the
  generated Verilog

## 1. Depend on ScalaHDL

Your build names the `core` project of your ScalaHDL checkout as a project
reference, which sbt resolves as a *source dependency*: the library is compiled
from source as part of your build, so there is no artifact to publish and no
version to keep in step.

## 2. Create a standalone project

Create this layout outside the ScalaHDL repository:

```text
my-counter/
├── build.sbt
├── project/
│   └── build.properties
└── src/
    └── main/
        └── scala/
            └── counter/
                ├── Counter.scala
                └── Generate.scala
```

Use this `build.sbt`. The library path is a value you set, so your checkout
does not have to be named `ScalaHDL` or sit beside this project:

```scala
ThisBuild / scalaVersion := "3.3.8"

// Where your ScalaHDL checkout is. Override on the command line with
//   sbt -Dscalahdl.root=/path/to/your/ScalaHDL
val scalahdlRoot = sys.props.getOrElse("scalahdl.root", "../ScalaHDL")

// The `core` project of that checkout, as a source dependency.
lazy val scalahdlCore = ProjectRef(file(scalahdlRoot), "core")

lazy val root = (project in file("."))
  .dependsOn(scalahdlCore)
  .settings(
    name := "my-counter",
    // The DSL reaches its operator surface through one deliberate implicit
    // conversion, `DslSignal → Expr`. `-feature` makes Scala 3 warn at each
    // use, so without these flags writing `a + b` compiles and says nothing.
    scalacOptions ++= Seq("-language:implicitConversions", "-feature")
  )
```

Use this `project/build.properties`:

```properties
sbt.version=1.10.7
```

Match the ScalaHDL build's Scala version: a source dependency requires a
compatible compiler.

## 3. Define a hardware module

Create `src/main/scala/counter/Counter.scala`:

```scala
package counter

import scalahdl.dsl.{*, given}
import scalahdl.elab.Module
import scalahdl.clock.ResetKind
import scalahdl.core.ResetPolarity.ActiveHigh

class Counter extends Module:
  val clk   = In(Clock())
  val rst   = In(Reset(ActiveHigh))
  val en    = In(Bool())
  val count = Out(UInt(8))

  given defaultDomain: ClockDomain =
    ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)

  val cnt = Reg(UInt(8), init = 0.U(8))
  when(en) { cnt := cnt + 1.U(8) }
  count := cnt
```

A ScalaHDL module is an ordinary Scala class extending `scalahdl.elab.Module`.
Its class body declares the hardware:

- `In` and `Out` create explicit ports.
- `ClockDomain` associates sequential logic with explicit clock and reset ports.
- `Reg` creates a register in the given domain.
- `when` and `:=` describe conditional updates and connections; `when(c) { ... }
  .elsewhen(d) { ... }.otherwise { ... }` is one chain, and `switch(sel) {
  is(v) { ... }; default { ... } }` is a multi-way partition that emits a
  native Verilog `case`.
- Scala `val` names, such as `cnt`, are preserved in emitted Verilog.

The clock-domain `given` must appear before registers that use it.

### Saying what the module claims about itself

A module can state an invariant beside the logic that maintains it:

```scala
  simOnly {
    check(!(en & cnt === 255.U(8)), "the counter is about to wrap while enabled")
  }
```

which emits at the bottom of the module, inside `// synthesis translate_off` so
that no board carries it:

```verilog
  always @(posedge clk) begin
    if (!rst) begin
       if (!((~(en & (cnt == 8'd255))))) begin
         $display("[%0t] Counter: the counter is about to wrap while enabled", $time);
         $stop;
       end
    end
  end
```

The check is evaluated once per clock edge, and not on an edge taken while
reset is asserted — the guard comes from the clock domain, so no check restates
it. **Nothing inside `simOnly` may become part of the design**: an assignment, a
`Wire`, a `Reg`, a memory write or an instance is an elaboration error, which is
what makes a module with the block bit-identical to the same module without it.
A failed check ends the simulation with a non-zero status, so a script that runs
a testbench fails on a broken invariant without being told about it.

## 4. Add a generator

Generation belongs in the design project, not in ScalaHDL. Create
`src/main/scala/counter/Generate.scala`:

```scala
package counter

import scalahdl.emit.Emission

object Generate:
  def main(args: Array[String]): Unit =
    val outputDir = args.headOption.getOrElse("generated/verilog")
    val design = new Counter().elaborateDesign()
    val emission = new Emission()
    val emitted = emission.emit(design)
    emission.writeTo(emitted, outputDir)

    emitted.files.foreach { case (name, _) =>
      println(s"Wrote $outputDir/$name")
    }
    emitted.reports.foreach { case (name, _) =>
      println(s"Wrote $outputDir/$name")
    }
    println(s"Wrote $outputDir/manifest.f")
```

The generator constructs the no-argument top module, elaborates and validates
its complete reachable hierarchy, emits it in memory, and writes the artifacts.
Validation errors stop generation and are reported before invalid Verilog can
be written.

## 5. Generate Verilog

From the standalone project's root, run:

```sh
sbt "runMain counter.Generate generated/verilog"
```

The output directory will contain:

```text
generated/verilog/
├── Counter.v
├── Counter.cdc.rpt
├── Counter.levels.rpt
├── design.levels.rpt
└── manifest.f
```

`Counter.v` is the generated Verilog-2001 module. `Counter.cdc.rpt` is the CDC
report. `Counter.levels.rpt` is the logic-level report for that module, the graph
depth of each cone, and `design.levels.rpt` rolls those up across the hierarchy.
`manifest.f` lists emitted Verilog files in dependency order and can be passed to
downstream tools that accept a file list. A module that declares a state machine
also emits `<name>.fsm.md`; `Counter` declares none, so it emits none.

With Verilator installed, lint the result with:

```sh
verilator --lint-only -Wall generated/verilog/Counter.v
```

For a hierarchy, ScalaHDL emits one Verilog file per distinct module, with
children before parents in `manifest.f`.

## Normal development workflow

1. Edit modules and generators in the standalone design project.
2. Run the generator again; existing generated files of the same name are
   replaced deterministically.
3. Run simulation, lint, synthesis, or other downstream tools on the generated
   Verilog.
4. Commit the Scala design sources. Decide separately whether generated Verilog
   is a checked-in deliverable or a build artifact.
5. Only if the ScalaHDL library source changes, rebuild the design project: the
   source dependency recompiles the library automatically, with no republish
   step.

## Test behaviour, not emitted text

Test the hardware by its behaviour: simulate it, compare it against a reference
model, or run it. Do not read the emitted Verilog as a string and search it for a
line or a shape — that fails when the library improves its formatting, although
the design did not change. If your design needs a structural property checked,
assert it in this repository on the smallest design that shows it, not in the
consumer's suite.

## Troubleshooting

**`sbt` cannot find the ScalaHDL project.** The source dependency names a path
that must exist. If your checkout is not at the default `../ScalaHDL`, set it:
`sbt -Dscalahdl.root=/path/to/ScalaHDL`. The error sbt raises names the path it
tried, which is the one in `build.sbt`.

**A design does not validate and the run stops.** That is deliberate: the
generator prints a diagnostic — the offending source line with a caret
under the wrong column, and a `note:` for the other location — and exits
non-zero. No invalid Verilog is written. If you see the location but no source
line and caret, the generator could not find the design's source file; run it
from the repository root so the excerpt resolves.

**The emitted Verilog will not lint.** Ensure the tool reads Verilog-2001 or
newer; `verilator --lint-only -Wall` is the check the library's own suites use.
Some warnings are tool-specific (Verilator renames C++ keywords); they are not
library defects.

**A name is rejected as `NAME_RESERVED`.** The emitted file is read as
SystemVerilog, so a signal, port, instance, or memory named after one of IEEE
1800-2017's reserved words is refused at its declaration, not escaped.
Rename it.

**`a + b` warns at every operator, or a feature-use warning appears.** Add
both `-language:implicitConversions` and `-feature` to `scalacOptions`, as the
`build.sbt` above does; the first admits the DSL's one conversion, the second
makes the compiler name where it is applied.

## Temporary in-repository fallback

If a separate project is temporarily inconvenient, clone ScalaHDL and add a
design under `examples/src/main/scala/scalahdl/examples/`. Give the top module a
no-argument constructor, then run:

```sh
./emit.sh scalahdl.examples.YourTop generated/verilog
```

This works because the repository's `examples` subproject directly depends on
`core`. Prefer this over copying only the `examples` directory: by itself that
directory is not a standalone build. This fallback is useful while prototyping,
but it mixes application code with the library checkout, complicates version
control and upgrades, and should not be the normal project structure.

## Where to go next

[`examples/README.md`](examples/README.md) indexes every example by the feature
it shows. For the design contract, see [`hdl-spec.md`](hdl-spec.md); for
implementation progress, see [`roadmap/README.md`](roadmap/README.md).
