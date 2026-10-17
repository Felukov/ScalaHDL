package scalahdl.examples

import scalahdl.dsl.{*, given}
import scalahdl.elab.{Module, param}
import scalahdl.clock.ResetKind
import scalahdl.core.ResetPolarity.ActiveHigh

/** A pipelined multiply-accumulate, in the shape of a hard DSP slice:
  * `p <= a * b + c`, with one register stage per operand and `c` delayed two
  * cycles to meet the product.
  *
  * It is here for the width discipline rather than the arithmetic. Every
  * widening in the datapath is written, and each one is a different rule:
  *
  *   - `a_r * b_r` needs no `resize` at all. The product is lossless, so R21.3
  *     exempts `*` from width agreement and gives the result `SIZE_A + SIZE_B`
  *     bits — the one arithmetic operator that decides nothing about which bits
  *     survive.
  *   - `m_r + c_r(1)` is not exempt (R6). `+` yields its *operand* width with
  *     the carry dropped, so the accumulator's headroom bit cannot appear in
  *     the result — it has to exist in the operands, which is why both are
  *     resized to `SIZE_P` first.
  *   - Both resizes are widening on an `SInt`, so each emits a replication of
  *     the operand's sign bit and each therefore needs a bit-addressable
  *     operand (R21.4/R6.2). `m_r` is a signal and `c_r(1)` a `Vec` element of
  *     one; both qualify.
  *
  * `SIZE_P` is `max(SIZE_M, SIZE_C) + 1` for the same reason: the addends widen
  * to the wider of the two, and the carry out of that width needs one bit more.
  * Writing it as `SIZE_M + 1` — the natural guess when `c` is assumed no wider
  * than the product — makes the `c` resize a *narrowing* one whenever it is,
  * and it truncates silently. The width rules catch the mismatched `+`; they
  * cannot catch a resize that was asked for. */
class DslExample(
                  SIZE_A   : Int,
                  SIZE_B   : Int,
                  SIZE_C   : Int,
                  SIZE_OUT : Int
                ) extends Module(param("a", SIZE_A), param("b", SIZE_B),
                                 param("c", SIZE_C), param("o", SIZE_OUT)):

  val SIZE_M = SIZE_A + SIZE_B
  val SIZE_P = math.max(SIZE_M, SIZE_C) + 1

  val clk  = In(Clock())
  val rst  = In(Reset(ActiveHigh))
  val a    = In(SInt(SIZE_A))
  val b    = In(SInt(SIZE_B))
  val c    = In(SInt(SIZE_C))
  val dout = Out(SInt(SIZE_OUT))

  given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)

  val a_r = Reg(SInt(SIZE_A))
  val b_r = Reg(SInt(SIZE_B))
  val c_r = Reg(Vec(2, SInt(SIZE_C)))
  val m_r = Reg(SInt(SIZE_M))
  val p_r = Reg(SInt(SIZE_P))

  a_r := a
  b_r := b
  c_r(0) := c
  c_r(1) := c_r(0)
  m_r := a_r * b_r
  p_r := m_r.resize(SIZE_P) + c_r(1).resize(SIZE_P)

  dout := p_r.resize(SIZE_OUT)
