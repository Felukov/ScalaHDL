package scalahdl.aggregates

import scalahdl.dsl.{*, given}
import scalahdl.elab.Module
import scalahdl.clock.ResetKind
import scalahdl.core.*
import scalahdl.core.ResetPolarity.ActiveHigh
import scalahdl.emit.VerilogEmitter

/** R14.3 — `decode` one-hot helper and the `Mux` idiom for combinational
  * dynamic-index writes. No implicit write-decode combinator exists. */
class DecodeSuite extends munit.FunSuite:

  abstract class Fix extends Module:
    val clk = In(Clock()); val rst = In(Reset(ActiveHigh))
    given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)

  test("decode(i, 4) emits a one-hot Vec of 4 Bools"):
    class Top extends Fix:
      val i = In(UInt(2))
      val sel = decode(i, 4)
      val o = Out(Vec(4, Bool()))
      for j <- 0 until 4 do
        o(j) := sel(j)
    val design = (new Top).elaborateDesign()
    val v = new VerilogEmitter("0.1.0-SNAPSHOT").emitModule(design.top, "Top")
    // sel is an internal Vec[Bool] → struct-of-arrays: sel [0:3].
    assert(v.contains("sel [0:3]"), s"sel should be a struct-of-arrays: $v")
    // Each element: sel(j) := (i === j). Emits should contain i == 0/3.
    assert(v.contains("(i == 2'd0)"), s"should compare i to 0: $v")
    assert(v.contains("(i == 2'd3)"), s"should compare i to 3: $v")

  test("the R14.3 idiom (Mux + decode) compiles and emits per-element Mux"):
    class Top extends Fix:
      val i = In(UInt(2))
      val x = In(UInt(8))
      val d = In(UInt(8))
      val sel = decode(i, 4)
      val outs = Wire(Vec(4, UInt(8)))
      for j <- 0 until 4 do
        outs(j) := Mux(sel(j), x, d)
      val q = Out(UInt(8))
      q := outs(i)
    val design = (new Top).elaborateDesign()
    val v = new VerilogEmitter("0.1.0-SNAPSHOT").emitModule(design.top, "Top")
    // R2: Mux is an anonymous expression and must not invent a declaration.
    assert(v.contains("? x : d"), s"Mux should emit inline at each explicit assignment: $v")
    assert(!v.contains("wire [7:0] mux"), s"Mux must not invent hidden signals (R2): $v")

  test("no writeDecoded helper exists in the public API"):
    // R14.3: the library provides no implicit write-decoding combinator. A
    // grep of the DSL source confirms no `def writeDecoded` definition.
    val src = scala.io.Source.fromFile(
      "core/src/main/scala/scalahdl/dsl/Decode.scala").mkString
    assert(!src.contains("def writeDecoded"),
      s"no writeDecoded definition in Decode.scala")
