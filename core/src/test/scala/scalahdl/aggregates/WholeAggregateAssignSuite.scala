package scalahdl.aggregates

import scalahdl.dsl.{*, given}
import scalahdl.elab.Module
import scalahdl.clock.ResetKind
import scalahdl.core.ResetPolarity.ActiveHigh
import scalahdl.emit.VerilogEmitter

/** R07.1 — a whole-aggregate assignment at the Verilog-2001 boundary.
  *
  * `dst := src` on a Bundle or a Vec is one statement in the IR (R17 preserves
  * the aggregate) and one statement per leaf in the output, because
  * Verilog-2001 has no aggregate to assign. The pairing is by field path, not
  * by position, and each side is spelled by its own context: a port Vec is
  * per-element, an internal Vec is a net array (R14.1). */
class WholeAggregateAssignSuite extends munit.FunSuite:

  case class Pair(w: Int) extends Bundle:
    val lo = UInt(w)
    val hi = UInt(w)

  private def emit(top: Module): String =
    new VerilogEmitter("0.1.0-SNAPSHOT").emitModule(top.elaborateDesign().top, "Top")

  test("a bundle assignment emits one statement per leaf, in declaration order"):
    class Top extends Module:
      val clk = In(Clock()); val rst = In(Reset(ActiveHigh))
      given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)
      val in = In(Pair(8))
      val out = Out(Pair(8))
      out := in

    val v = emit(new Top)
    assert(v.contains("out_lo = in_lo;"), v)
    assert(v.contains("out_hi = in_hi;"), v)
    // The root name is not a signal in the output — nothing may be assigned by it.
    assert(!v.contains(" out = "), v)
    assertEquals(v.indexOf("out_lo = in_lo;") < v.indexOf("out_hi = in_hi;"), true,
      s"leaves emit in declaration order: $v")

  test("a nested bundle assignment reaches every leaf of the nesting"):
    case class Outer(w: Int) extends Bundle:
      val flag = Bool()
      val pair = field(Pair(w))

    class Top extends Module:
      val clk = In(Clock()); val rst = In(Reset(ActiveHigh))
      given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)
      val in = In(Outer(8))
      val out = Out(Outer(8))
      out := in

    val v = emit(new Top)
    assert(v.contains("out_flag = in_flag;"), v)
    assert(v.contains("out_pair_lo = in_pair_lo;"), v)
    assert(v.contains("out_pair_hi = in_pair_hi;"), v)

  test("a Vec assignment takes each side's own lowering: per-element at a port, array inside"):
    class Top extends Module:
      val clk = In(Clock()); val rst = In(Reset(ActiveHigh))
      given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)
      val entries = Reg(Vec(2, UInt(8)), init = null)
      val out = Out(Vec(2, UInt(8)))
      out := entries

    val v = emit(new Top)
    // R14.1 — the port side is per-element, the internal side is a net array.
    assert(v.contains("out_0 = entries[0];"), v)
    assert(v.contains("out_1 = entries[1];"), v)

  test("a multi-leaf assignment inside a `when` is wrapped, so every leaf stays under the `if`"):
    class Top extends Module:
      val clk = In(Clock()); val rst = In(Reset(ActiveHigh))
      given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)
      val enable = In(Bool())
      val in = In(Pair(8))
      val out = Out(Pair(8))
      out := in
      when(enable):
        out := in

    val v = emit(new Top)
    // One IR statement, two emitted ones: written bare, the second leaf would
    // run unconditionally.
    val conditional = v.linesIterator.dropWhile(!_.contains("if (enable)")).take(4).toList
    assert(conditional.head.contains("begin"), conditional.mkString("\n"))
    assert(conditional.exists(_.contains("out_lo = in_lo;")), conditional.mkString("\n"))
    assert(conditional.exists(_.contains("out_hi = in_hi;")), conditional.mkString("\n"))
    assert(conditional.exists(_.trim == "end"), conditional.mkString("\n"))

  // ---- R07/R07.2, M43: a bundle reads as itself, and reads as its bits
  //
  // `Bundle → DslSignal → Expr` is two conversions and Scala will not chain
  // them, so every read of a whole bundle once named `.whole` explicitly. The
  // second conversion removes that; R07.2 then says what the read *means*.

  test("a bundle is its own right-hand side, with no accessor"):
    class Top extends Module:
      val clk = In(Clock()); val rst = In(Reset(ActiveHigh))
      given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)
      val in = In(Pair(8))
      val out = Out(Pair(8))
      out := in

    // Aggregate to aggregate is the structural form: paired by field, one
    // statement per leaf, and no concatenation anywhere.
    val v = emit(new Top)
    assert(v.contains("out_lo = in_lo;"), v)
    assert(v.contains("out_hi = in_hi;"), v)

  test("...and at a scalar target it is the bundle's packed word"):
    class Top extends Module:
      val clk = In(Clock()); val rst = In(Reset(ActiveHigh))
      given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)
      val in = In(Pair(8))
      val out = Out(Bits(16))
      out := in

    assert(emit(new Top).contains("= {in_lo, in_hi};"), emit(new Top))

  test("Cat of a bundle emits the leaves it means"):
    class Top extends Module:
      val clk = In(Clock()); val rst = In(Reset(ActiveHigh))
      given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)
      val in = In(Pair(8))
      val out = Out(Bits(32))
      out := Cat(in, in).asBits

    // The hole this closed: a bundle has no single name in Verilog-2001 — its
    // port is `in_lo`/`in_hi` — and this once emitted `{in, in}`.
    val v = emit(new Top)
    assert(v.contains("{{in_lo, in_hi}, {in_lo, in_hi}}"), v)

  test("...and `Fill` is the same surface, so it reads the same way"):
    class Top extends Module:
      val clk = In(Clock()); val rst = In(Reset(ActiveHigh))
      given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)
      val in = In(Pair(8))
      val out = Out(Bits(32))
      out := Fill(2, in).asBits

    assert(emit(new Top).contains("{2{{in_lo, in_hi}}}"), emit(new Top))

  test("a bundle at a narrower scalar is a width error, like any other read"):
    class Top extends Module:
      val clk = In(Clock()); val rst = In(Reset(ActiveHigh))
      given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)
      val in = In(Pair(8))
      val out = Out(Bits(8))
      out := in

    val error = intercept[scalahdl.diag.ValidationFailed]((new Top).elaborateDesign())
    assert(error.diagnostics.exists(_.summary.contains("width-16")),
      error.diagnostics.map(_.summary).mkString("\n"))

  test("...and the kind still has to agree: a packed word is Bits, not UInt"):
    class Top extends Module:
      val clk = In(Clock()); val rst = In(Reset(ActiveHigh))
      given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)
      val in = In(Pair(8))
      val out = Out(UInt(16))
      out := in

    val error = intercept[scalahdl.diag.ValidationFailed]((new Top).elaborateDesign())
    assert(error.diagnostics.exists(_.fix.exists(_.contains("asUInt"))),
      error.diagnostics.map(d => d.summary + " / " + d.fix).mkString("\n"))

  test("bits do not become a bundle by width alone — R14 still holds"):
    class Top extends Module:
      val clk = In(Clock()); val rst = In(Reset(ActiveHigh))
      given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)
      val sel = In(Bool())
      val a = In(Pair(8)); val b = In(Pair(8))
      val out = Out(Pair(8))
      out := Mux(sel, a, b)

    // The arms read as bits, so the `Mux` is a 16-bit word — and a word has no
    // field names to land on. This is the SystemVerilog assignment R14 refuses;
    // `unpackBits` is the way in.
    val error = intercept[scalahdl.diag.ValidationFailed]((new Top).elaborateDesign())
    assert(error.diagnostics.exists(_.summary.contains("aggregate shapes must match")),
      error.diagnostics.map(_.summary).mkString("\n"))
