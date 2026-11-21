package scalahdl.aggregates

import scalahdl.dsl.{*, given}
import scalahdl.elab.Module
import scalahdl.clock.ResetKind
import scalahdl.core.*
import scalahdl.core.ResetPolarity.ActiveHigh
import scalahdl.diag.{ValidationFailed, Code}
import scalahdl.emit.VerilogEmitter

/** R14 — aggregates are not a register-only feature. A `Wire` takes every shape
  * a `Reg` does, and indexing one returns `T` with full type information, so
  * `entries(2).addr` type-checks and yields a `UInt`.
  *
  * The rule that *does* separate the two is R14.2, and only that one: a
  * runtime-index write is legal on a `Reg` Vec and an error on a combinational
  * one. These tests pin both halves — the shapes a wire now accepts, and the
  * one thing making it a wire still forbids. */
class WireAggregateSuite extends munit.FunSuite:

  abstract class Fix extends Module:
    val clk = In(Clock()); val rst = In(Reset(ActiveHigh))
    given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)

  case class Req(w: Int) extends Bundle:
    val addr = UInt(w)
    val prot = Bits(3)

  def expectFailure(body: => Module): List[scalahdl.diag.Diagnostic] =
    val ex = intercept[ValidationFailed] { body.elaborateDesign() }
    ex.diagnostics

  test("Wire(Bundle) binds the field leaves, as Reg(Bundle) does"):
    class Top extends Fix:
      val src = In(Req(8))
      val staged = Wire(Req(8))
      val q = Out(UInt(8))
      staged.addr := src.addr
      staged.prot := src.prot
      q := staged.addr
    val design = (new Top).elaborateDesign()
    val sig = design.top.signals.find(_.name == "staged").getOrElse(fail("no staged signal"))
    assert(sig.typeOf.isInstanceOf[Type.Bundle], s"staged should be a Bundle: ${sig.typeOf}")
    assertEquals(sig.kind, SignalKind.Wire)
    val v = new VerilogEmitter("0.1.2").emitModule(design.top, "Top")
    assert(v.contains("staged_addr"), s"should emit the addr leaf: $v")
    assert(v.contains("staged_prot"), s"should emit the prot leaf: $v")

  test("Wire(Vec(n, Bundle)) keeps the element type: entries(2).addr is a UInt (R14)"):
    class Top extends Fix:
      val src = In(Req(8))
      val entries = Wire(Vec(4, Req(8)))
      val probe = Out(UInt(8))
      for j <- 0 until 4 do
        entries(j).addr := src.addr
        entries(j).prot := src.prot
      // The point of the milestone: this line type-checks without a `.as` view.
      probe := entries(2).addr
      // R14 is a claim about the *type*, so assert it and not only that it compiled.
      assertEquals(entries(2).addr.typeOf, Type.UInt(8))
      assertEquals(entries(2).prot.typeOf, Type.Bits(3))
    (new Top).elaborateDesign()   // must not throw

  test("a combinational Vec-of-bundle lowers to struct-of-arrays (R14.1 internal form)"):
    class Top extends Fix:
      val src = In(Req(8))
      val entries = Wire(Vec(4, Req(8)))
      val probe = Out(UInt(8))
      for j <- 0 until 4 do
        entries(j).addr := src.addr
        entries(j).prot := src.prot
      probe := entries(2).addr
    val design = (new Top).elaborateDesign()
    val v = new VerilogEmitter("0.1.2").emitModule(design.top, "Top")
    // One net array per leaf, not one per element (R14.1 picks the form from
    // In/Out vs internal — not from Reg vs Wire).
    assert(v.contains("entries_addr [0:3]"), s"addr leaf should be a net array: $v")
    assert(v.contains("entries_prot [0:3]"), s"prot leaf should be a net array: $v")
    assert(!v.contains("entries_0_addr"), s"internal Vec must not expand per element: $v")
    assert(v.contains("entries_addr[2]"), s"constant-index read is a native array read: $v")

  test("a runtime-index write on a combinational Vec-of-bundle is still an error (R14.2)"):
    class Bad extends Fix:
      val i = In(UInt(2))
      val src = In(Req(8))
      val entries = Wire(Vec(4, Req(8)))
      entries(i).addr := src.addr
      entries(i).prot := src.prot
    val errs = expectFailure(new Bad)
    assert(errs.exists(_.code == Code.DYNAMIC_WRITE_COMBINATIONAL),
      s"expected DYNAMIC_WRITE_COMBINATIONAL: ${errs.map(_.render)}")

  test("the same write on a Reg Vec-of-bundle is legal — R14.2 is the only difference"):
    class Good extends Fix:
      val i = In(UInt(2))
      val src = In(Req(8))
      val entries = Reg(Vec(4, Req(8)), init = null)
      entries(i).addr := src.addr
      entries(i).prot := src.prot
    val design = (new Good).elaborateDesign()   // must not throw
    assert(design.top.signals.exists(_.name == "entries"))

  test("an undriven element leaf on a combinational Vec is caught per leaf (R11/R14)"):
    class Bad extends Fix:
      val src = In(Req(8))
      val entries = Wire(Vec(4, Req(8)))
      val probe = Out(UInt(8))
      // element 3 is never driven — a wire owes a value on every path.
      for j <- 0 until 3 do
        entries(j).addr := src.addr
        entries(j).prot := src.prot
      probe := entries(2).addr
    val errs = expectFailure(new Bad)
    assert(errs.exists(_.code == Code.COMBINATIONAL_INCOMPLETE),
      s"expected COMBINATIONAL_INCOMPLETE: ${errs.map(_.render)}")
    assert(errs.exists(_.render.contains("entries(3).addr")),
      s"the diagnostic should name the undriven leaf: ${errs.map(_.render)}")
