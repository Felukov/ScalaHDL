package scalahdl.golden

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.sys.process.*

import scalahdl.elab.Module
import scalahdl.emit.Emission
import scalahdl.examples.{DecodeTable, FetchFsm}

/** R22/R20.1 — the two M14 examples, emitted, reproducible and linted.
  *
  * `FetchFsm` is the milestone's motivating design: one `switch(state)` where
  * four equality-tested `when` blocks used to be. `DecodeTable` is the other
  * half of the requirement — alternatives produced by a Scala `for` are the
  * same first-class IR. What this suite asserts is what an example promises: it
  * emits one file per module, reproducibly (R20.1), and Verilator accepts the
  * syntax of every nesting. The emitted bytes are the emitter's contract,
  * pinned by the `core` corpus. */
final class SwitchCaseGoldenSuite extends munit.FunSuite:
  private val version = "0.1.0-SNAPSHOT"

  private def emit(m: => Module): String =
    new Emission(version).emit(m.elaborateDesign()).files.head._2

  private def lint(name: String, verilog: String): Unit =
    assume(Seq("sh", "-c", "command -v verilator >/dev/null 2>&1").! == 0,
      "Verilator not on PATH; skipping R22's lint criterion")
    val dir = Files.createTempDirectory("scalahdl-switch-lint")
    try
      val file = dir.resolve(name)
      Files.writeString(file, verilog, StandardCharsets.UTF_8)
      val output = new StringBuilder
      val code = Process(Seq("verilator", "--lint-only", "-Wall", file.toString)).!(
        ProcessLogger(line => output.append(line).append('\n'),
                      line => output.append(line).append('\n')))
      assertEquals(code, 0, s"verilator --lint-only -Wall rejected $name:\n$output")
    finally
      val _ = Seq("rm", "-rf", dir.toString).!

  test("both examples are byte-for-byte reproducible (R20.1)"):
    assertEquals(emit(new FetchFsm), emit(new FetchFsm))
    assertEquals(emit(new DecodeTable), emit(new DecodeTable))

  test("emitted switch Verilog passes verilator --lint-only -Wall"):
    lint("FetchFsm.v", emit(new FetchFsm))
    lint("DecodeTable.v", emit(new DecodeTable))

  test("switches nested in each other and in `when` arms are legal Verilog"):
    // Every position a `case` can end up in: an `if` branch, an `else` branch,
    // and another case item. The construction's emitted shape is pinned in
    // `core`; what is checked here is that each nesting emits Verilog the tools
    // accept.
    lint("NestedSwitchLint.v", emit(new NestedSwitchLint))

/** Mixed nesting, kept in the test sources because it exists to be linted
  * rather than to be read as a design (compare `ExpressionLintSuite`). */
final class NestedSwitchLint extends scalahdl.elab.Module:
  import scalahdl.dsl.{*, given}
  import scalahdl.clock.ResetKind
  import scalahdl.core.ResetPolarity.ActiveHigh

  val clk = In(Clock())
  val rst = In(Reset(ActiveHigh))
  given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)

  val go = In(Bool())
  val sel = In(UInt(2))
  val inner = In(UInt(2))
  val y = Out(UInt(8))

  val held = Reg(UInt(8), init = 0.U(8))
  switch(sel) {
    is(0.U(2)) { held := held + 1.U(8) }
    default { held := 0.U(8) }
  }

  y := held
  when(go) {
    switch(sel) {
      is(0.U(2)) { y := 1.U(8) }
      is(1.U(2)) {
        switch(inner) {
          is(0.U(2)) { y := 2.U(8) }
          default { y := 3.U(8) }
        }
      }
      default { y := 4.U(8) }
    }
  }.otherwise {
    switch(sel) {
      is(3.U(2)) { y := 5.U(8) }
      default { y := 6.U(8) }
    }
  }
