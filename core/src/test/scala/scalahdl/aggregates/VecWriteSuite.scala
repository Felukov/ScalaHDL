package scalahdl.aggregates

import scalahdl.dsl.{*, given}
import scalahdl.elab.Module
import scalahdl.clock.ResetKind
import scalahdl.core.*
import scalahdl.core.ResetPolarity.ActiveHigh
import scalahdl.diag.{ValidationFailed, Code}

/** R14.2 — dynamic-index writes require registers. A runtime-index write on a
  * `Reg` Vec is legal (variable index in the `always` block); on a
  * combinational Vec it is an error. Constant-index writes are legal on
  * both. */
class VecWriteSuite extends munit.FunSuite:

  abstract class Fix extends Module:
    val clk = In(Clock()); val rst = In(Reset(ActiveHigh))
    given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)

  def expectFailure(body: => Module): List[scalahdl.diag.Diagnostic] =
    val ex = intercept[ValidationFailed] { body.elaborateDesign() }
    ex.diagnostics

  test("runtime-index write on a Reg Vec elaborates cleanly"):
    class Good extends Fix:
      val i = In(UInt(2))
      val x = In(UInt(8))
      val entries = Reg(Vec(4, UInt(8)), init = null)
      entries(i) := x
    val design = (new Good).elaborateDesign()   // must not throw
    assert(design.top.signals.exists(_.name == "entries"))

  test("runtime-index write on a combinational Vec is an error (R14.2)"):
    class Bad extends Fix:
      val i = In(UInt(2))
      val x = In(UInt(8))
      val entries = Wire(Vec(4, UInt(8)))
      entries(i) := x
    val errs = expectFailure(new Bad)
    assert(errs.exists(_.code == Code.DYNAMIC_WRITE_COMBINATIONAL),
      s"expected DYNAMIC_WRITE_COMBINATIONAL: ${errs.map(_.render)}")

  test("constant-index writes are legal on a combinational Vec when every element is covered"):
    class Good extends Fix:
      val x = In(UInt(8))
      val entries = Wire(Vec(4, UInt(8)))
      for j <- 0 until 4 do entries(j) := x
    val design = (new Good).elaborateDesign()   // must not throw
    assert(design.top.signals.exists(_.name == "entries"))

  test("runtime scalar bit write is not mistaken for a dynamic Vec write"):
    class Good extends Fix:
      val i = In(UInt(3))
      val flag = In(Bool())
      val word = Wire(Bits(8))
      word(i) := flag
    val design = (new Good).elaborateDesign()
    assert(design.top.signals.exists(_.name == "word"))

  test("constant Vec index is bounds checked before emission"):
    class Bad extends Fix:
      val entries = Reg(Vec(4, UInt(8)), init = null)
      val q = Out(UInt(8))
      q := entries(4)
    val errs = expectFailure(new Bad)
    assert(errs.exists(_.code == Code.VEC_INDEX_OUT_OF_BOUNDS),
      s"expected VEC_INDEX_OUT_OF_BOUNDS: ${errs.map(_.render)}")

  test("runtime read from a flattened port Vec is a structured error"):
    class Bad extends Fix:
      val i = In(UInt(2))
      val entries = In(Vec(4, UInt(8)))
      val q = Out(UInt(8))
      q := entries(i)
    val errs = expectFailure(new Bad)
    assert(errs.exists(_.code == Code.RUNTIME_INDEX_PORT_VEC),
      s"expected RUNTIME_INDEX_PORT_VEC: ${errs.map(_.render)}")
