package scalahdl.clock

import scalahdl.core.ResetPolarity
import scalahdl.core.ResetPolarity.{ActiveHigh, ActiveLow}
import scalahdl.dsl.{*, given}
import scalahdl.elab.Module
import scalahdl.emit.Emission

/** R13.7 — a reset derived rather than received, once and explicitly.
  *
  * R13.5 got a reset *out* of `Type.Reset` and into data, and its own rationale
  * names the case this rule exists for — *"a soft reset ORed in"*. There was no
  * way back: `Wire(Reset(…))` could be declared and nothing could be assigned
  * to it, because R6.5 refuses `Bool → Reset` and the conversion set stopped at
  * `.asUInt`/`.asSInt`/`.asBits`. So a module could compute its own reset and
  * then not use it, and `ClockDomain`'s R13.1 type guard — which is right —
  * turned that into a wall.
  *
  * Found porting a Zilog 8530. `scc.v` derives its soft reset from a register
  * write:
  *
  * {{{
  * assign reset = ((wreg_a | wreg_b) & (rindex == 9) & (wdata[7:6] == 2'b11)) | reset_hw;
  * always @(posedge clk or posedge reset) ...
  * }}}
  *
  * — nine `always` blocks take it asynchronously, and the difference between
  * that and a synchronous clear is visible at `_irq` for a clock, so a port
  * held bit-exact has to be able to write it.
  *
  * The obligations: the polarity is *applied* rather than recorded, so the
  * round trip through `.asserted` is exact; the result is assignable to a
  * `Reset`-typed wire and usable as a domain's reset; and the operand is one
  * bit or it is a named error.
  */
class DerivedResetSuite extends munit.FunSuite:

  private def emit(m: => Module): String =
    new Emission("0.0.0-TEST").emit(m.elaborateDesign()).files.head._2

  /** The shape this was found in: a hardware reset ORed with a decode, used
    * asynchronously. */
  private class SoftReset(polarity: ResetPolarity) extends Module:
    override def moduleName = s"SoftReset_$polarity"
    val clk  = In(Clock())
    val hard = In(Reset(ActiveHigh))
    val cmd  = In(Bool())
    val out  = Out(UInt(8))
    given defaultDomain: ClockDomain = ClockDomain(clk, ResetKind.None)
    val derived = Wire(Reset(polarity))
    derived := (cmd | hard.asserted).asReset(polarity)
    private val derivedDomain = ClockDomain(clk, derived, ResetKind.Async, polarity)
    val counter = Reg(UInt(8), init = 0, domain = derivedDomain)
    counter := counter + 1
    out := counter

  test("an active-high derived reset is a sensitivity list and a branch"):
    val v = emit(new SoftReset(ActiveHigh))
    assert(v.contains("derived = (cmd | hard);"), v)
    assert(v.contains("always @(posedge clk or posedge derived) begin"), v)
    assert(v.contains("if (derived) begin\n      counter <= 8'd0;\n    end"), v)

  test("an active-low derived reset inverts, so `asserted` still means asserted"):
    val v = emit(new SoftReset(ActiveLow))
    // The argument says what *asserted* means, and this is where it is paid
    // for: the wire carries the active-low level, so the same source condition
    // asserts the reset either way round.
    assert(v.contains("derived = (!(cmd | hard));"), v)
    assert(v.contains("always @(posedge clk or negedge derived) begin"), v)
    assert(v.contains("if (!derived) begin\n      counter <= 8'd0;\n    end"), v)

  test("the round trip through `.asserted` is exact for either polarity"):
    // The conversion goes through a named wire, so the round trip is two
    // statements rather than one expression — and what makes it *exact* is that
    // the two inversions are the same inversion, applied and then undone.
    val high = emit {
      new Module:
        override def moduleName = "RoundTripHigh"
        val clk = In(Clock())
        val a   = In(Bool())
        val out = Out(Bool())
        given defaultDomain: ClockDomain = ClockDomain(clk, ResetKind.None)
        val r = Wire(Reset(ActiveHigh))
        r := a.asReset(ActiveHigh)
        out := r.asserted
    }
    assert(high.contains("r = a;"), high)
    assert(high.contains("out = r;"), high)

    val low = emit {
      new Module:
        override def moduleName = "RoundTripLow"
        val clk = In(Clock())
        val a   = In(Bool())
        val out = Out(Bool())
        given defaultDomain: ClockDomain = ClockDomain(clk, ResetKind.None)
        val r = Wire(Reset(ActiveLow))
        r := a.asReset(ActiveLow)
        out := r.asserted
    }
    assert(low.contains("r = (!a);"), low)
    assert(low.contains("out = (!r);"), low)

  test("a derived reset and a port reset are the same thing to a register"):
    // The point of the rule: one module, two domains on one clock, one of them
    // computed. R05 says a domain is its clock, so this is not a crossing.
    val v = emit {
      new Module:
        val clk  = In(Clock())
        val hard = In(Reset(ActiveHigh))
        val cmd  = In(Bool())
        val out  = Out(UInt(16))
        given defaultDomain: ClockDomain = ClockDomain(clk, hard, ResetKind.Async, ActiveHigh)
        val derived = Wire(Reset(ActiveHigh))
        derived := (cmd | hard.asserted).asReset(ActiveHigh)
        private val derivedDomain = ClockDomain(clk, derived, ResetKind.Async, ActiveHigh)
        val onHard = Reg(UInt(8), init = 0)
        val onSoft = Reg(UInt(8), init = 0, domain = derivedDomain)
        onHard := onHard + 1
        onSoft := onSoft + 1
        out := Cat(onHard, onSoft)
    }
    assert(v.contains("always @(posedge clk or posedge hard) begin"), v)
    assert(v.contains("always @(posedge clk or posedge derived) begin"), v)

  // ---- what it refuses

  test("a multi-bit operand is refused, and says why a reset is one bit"):
    val e = intercept[CastError] {
      (new Module:
        val clk = In(Clock())
        val w   = In(UInt(8))
        val out = Out(UInt(8))
        given defaultDomain: ClockDomain = ClockDomain(clk, ResetKind.None)
        val r = Wire(Reset(ActiveHigh))
        r := w.asReset(ActiveHigh)
        out := w
      ).elaborateDesign()
    }
    assert(e.getMessage.contains("one bit"), e.getMessage)
    assert(e.getMessage.contains("a reset is a level"), e.getMessage)

  test("an unwidthed literal is refused, naming the width"):
    val e = intercept[CastError] {
      (new Module:
        val clk = In(Clock())
        val out = Out(Bool())
        given defaultDomain: ClockDomain = ClockDomain(clk, ResetKind.None)
        val r = Wire(Reset(ActiveHigh))
        r := 1.asReset(ActiveHigh)
        out := r.asserted
      ).elaborateDesign()
    }
    assert(e.getMessage.contains("width"), e.getMessage)
