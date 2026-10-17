package scalahdl.elab

import scalahdl.dsl.{*, given}
import scalahdl.clock.ResetKind
import scalahdl.core.ResetPolarity.ActiveHigh

/** R3 — Typed elaboration context; no ambient globals. Hardware construction
  * requires `using Module`; constructing outside a module is a compile error;
  * two modules elaborated in one process don't see each other's signals. */
class ContextSuite extends munit.FunSuite:

  class Fix extends Module:
    val clk = In(Clock())
    val rst = In(Reset(ActiveHigh))
    given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)

  /** A function that builds hardware declares `using Module` in its signature
    * (R3) — call-site length is identical to the ambient-state version. */
  def counter(w: Int)(using Module, ClockDomain): DslSignal =
    val cnt = Reg(UInt(w), init = 0.U(w))
    cnt

  test("def counter(w)(using Module) type-checks and uses the context"):
    class M extends Fix:
      val c = counter(8)   // counter declares `cnt` internally and returns it
    val m = new M
    val design = m.elaborateDesign()
    // the signal declared inside counter(w) is `cnt`; `c` aliases it (same id)
    assert(design.top.signals.exists(_.name == "cnt"),
      s"counter's internal cnt should be in signals: ${design.top.signals.map(_.name)}")

  test("constructing hardware with no using Module is a compile error"):
    // `In(...)` requires `using Module`; with none in scope it must not compile.
    val errs = compileErrors("In(Bool())")
    assert(errs.nonEmpty, "expected a compile error constructing In(Bool()) with no using Module")
    assert(errs.contains("Module") || errs.contains("given"),
      s"expected a missing-given/Module error, got: $errs")

  test("two modules elaborated in one process are independent"):
    class A extends Fix:
      val a_sig = Reg(UInt(8), init = 0.U(8))
    class B extends Fix:
      val b_sig = Reg(UInt(8), init = 0.U(8))
    val da = (new A).elaborateDesign()
    val db = (new B).elaborateDesign()
    val aNames = da.top.signals.map(_.name).toSet
    val bNames = db.top.signals.map(_.name).toSet
    assert(aNames.contains("a_sig"))
    assert(bNames.contains("b_sig"))
    assert(!aNames.contains("b_sig"), "module A must not see module B's signals")
    assert(!bNames.contains("a_sig"), "module B must not see module A's signals")
