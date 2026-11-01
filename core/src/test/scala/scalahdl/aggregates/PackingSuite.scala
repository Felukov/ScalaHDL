package scalahdl.aggregates

import scalahdl.dsl.{*, given}
import scalahdl.elab.Module
import scalahdl.clock.ResetKind
import scalahdl.core.*
import scalahdl.core.ResetPolarity.ActiveHigh
import scalahdl.emit.VerilogEmitter

/** R07.2 — packing follows SystemVerilog packed structs: fields concatenate in
  * declaration order (first field in the MSBs) and a `Vec` packs descending
  * (element n-1 high).
  *
  * An aggregate *is* that word, so reading one needs no operation — the
  * concatenation appears because Verilog-2001 has no name for a whole bundle,
  * not because the caller asked for a conversion. Writing one keeps its
  * operation, `unpackBits`, because that is the direction where a packed word
  * and an aggregate can disagree about shape. */
class PackingSuite extends munit.FunSuite:

  case class Pair(w: Int) extends Bundle:
    val a = Bits(w)
    val b = Bits(w)

  private def emit(top: Module): String =
    new VerilogEmitter("0.1.1").emitModule(top.elaborateDesign().top, "Top")

  test("a bundle read as bits is a concat, first field in the MSBs"):
    // Bundle { a = Bits(4); b = Bits(4) }, a=0xA, b=0x5 → {a, b} = 8'hA5
    class Top extends Module:
      val clk = In(Clock()); val rst = In(Reset(ActiveHigh))
      given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)
      val p = In(Pair(4))
      val q = Out(Bits(8))
      q := p
    assert(emit(new Top).contains("{p_a, p_b}"), s"should emit {{p_a, p_b}}: ${emit(new Top)}")

  test("...and `.asBits` is the same word, said explicitly"):
    class Top extends Module:
      val clk = In(Clock()); val rst = In(Reset(ActiveHigh))
      given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)
      val p = In(Pair(4))
      val q = Out(Bits(8))
      q := p.asBits
    assert(emit(new Top).contains("{p_a, p_b}"), emit(new Top))

  test("unpackBits round-trips: slices a Bits into the bundle's fields"):
    class Top extends Module:
      val clk = In(Clock()); val rst = In(Reset(ActiveHigh))
      given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)
      val bits = In(Bits(8))
      val p = Out(Pair(4))
      unpackBits(p, bits)
    val v = emit(new Top)
    // unpackBits assigns slices: p_a = bits[7:4], p_b = bits[3:0].
    assert(v.contains("p_a = bits[7:4]"), s"should slice high nibble to p_a: $v")
    assert(v.contains("p_b = bits[3:0]"), s"should slice low nibble to p_b: $v")

  test("...and it is the only way in: a packed word does not assign to a bundle"):
    // The asymmetry R07.2 rests on. A bundle reads as bits because the bits
    // are defined; bits do not read as a bundle, because a word carries no
    // field names and matching by width alone is the SystemVerilog behaviour
    // R14 exists to refuse.
    class Bad extends Module:
      val clk = In(Clock()); val rst = In(Reset(ActiveHigh))
      given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)
      val bits = In(Bits(8))
      val p = Out(Pair(4))
      p := bits
    val error = intercept[scalahdl.diag.ValidationFailed]((new Bad).elaborateDesign())
    assert(error.diagnostics.exists(_.summary.contains("aggregate shapes must match")),
      error.diagnostics.map(_.summary).mkString("\n"))

  test("Vec of 4 UInt(8) packs descending (element 3 in MSBs)"):
    class Top extends Module:
      val clk = In(Clock()); val rst = In(Reset(ActiveHigh))
      given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)
      val xs = In(Vec(4, UInt(8)))
      val q = Out(Bits(32))
      q := xs
    // Port Vec → per-element scalars xs_0..xs_3, catted descending.
    assert(emit(new Top).contains("{xs_3, xs_2, xs_1, xs_0}"), emit(new Top))

  test("a Vec of bundles reads the same way, so the rule has no ragged half"):
    class Top extends Module:
      val clk = In(Clock()); val rst = In(Reset(ActiveHigh))
      given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)
      val xs = In(Vec(2, Pair(4)))
      val q = Out(Bits(16))
      q := xs
    assert(emit(new Top).contains("{{xs_1_a, xs_1_b}, {xs_0_a, xs_0_b}}"), emit(new Top))
