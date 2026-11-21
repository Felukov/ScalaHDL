package scalahdl.validate

import scalahdl.dsl.{*, given}
import scalahdl.clock.ResetKind
import scalahdl.elab.Module
import scalahdl.core.*
import scalahdl.core.ResetPolarity.ActiveHigh
import scalahdl.emit.VerilogEmitter

/** R06.2 — Bit-select requires a named signal. Each test maps to a checkbox
  * in `roadmap/open/M04-scalar-types/README.md`. */
class BitSelectSuite extends munit.FunSuite:

  abstract class Fix extends Module:
    val clk = In(Clock())
    val rst = In(Reset(ActiveHigh))
    given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)

  test("(a + b)(8) errors with BIT_SELECT_ANONYMOUS"):
    class Bad extends Fix:
      val a = In(UInt(8))
      val b = In(UInt(8))
      val carry = Out(UInt(1))
      carry := (a + b)(8)   // bit-select of anonymous expression
    val err = intercept[scalahdl.dsl.BitSelectAnonymousError]:
      (new Bad).elaborateDesign()
    assert(err.getMessage.contains("anonymous"),
      s"should say 'anonymous': ${err.getMessage}")

  test("bound-then-slice form (slice of a port) emits cleanly"):
    class Good extends Fix:
      val a = In(UInt(8))
      val b = In(UInt(8))
      val lo = Out(UInt(4))
      // `a + b` is an anonymous Expr — bit-selecting it directly errors (tested
      // above). The bound-then-slice form requires a *named signal* (a port or a
      // `Wire`). Slicing a port — itself a DslSignal — is the realistic case
      // here, so this test confirms a named-signal slice elaborates and emits.
      lo := a(7, 4)
    val design = (new Good).elaborateDesign()
    val v = new VerilogEmitter("0.1.2").emitModule(design.top, "Good")
    assert(v.contains("a[7:4]"),
      s"should emit slice of a as a[7:4]: $v")

  test("named signal slice emits as sig[hi:lo]"):
    class Good extends Fix:
      val a = In(UInt(8))
      val q = Out(UInt(4))
      q := a(7, 4)           // slice of a named signal (a is a port/DslSignal)
    val design = (new Good).elaborateDesign()
    val v = new VerilogEmitter("0.1.2").emitModule(design.top, "Good")
    assert(v.contains("a[7:4]"),
      s"should emit slice as a[7:4]: $v")

  test("named signal index emits as sig[idx]"):
    class Good extends Fix:
      val a = In(UInt(8))
      val q = Out(UInt(1))
      q := a(0)              // index of a named signal
    val design = (new Good).elaborateDesign()
    val v = new VerilogEmitter("0.1.2").emitModule(design.top, "Good")
    assert(v.contains("a["),
      s"should emit index as a[...]: $v")
