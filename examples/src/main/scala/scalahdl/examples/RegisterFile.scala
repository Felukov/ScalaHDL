package scalahdl.examples

import scalahdl.dsl.{*, given}
import scalahdl.elab.Module
import scalahdl.clock.ResetKind
import scalahdl.core.ResetPolarity.ActiveHigh

/** RegisterFile — the aggregates example: a small register file over the
  * bundle and `Vec` surface.
  *
  *   - `Req` is a direction-free bundle (`addr`/`prot`); `In(Req(8))` applies
  *     one direction to all leaves → `req_addr`, `req_prot` (R07).
  *   - `rd` is a `Vec` of bundles at a port → per-element scalars `rd_0_addr`,
  *     … (R14.1 port form).
  *   - `entries` is the same `Vec` as an internal `Reg` → struct-of-arrays
  *     `entries_addr [0:3]` (R14.1 internal form).
  *   - `entries(waddr) := req` is a runtime-index write on a `Reg` Vec — legal
  *     (R14.2), lowering to `entries_addr[waddr] <= req_addr;`.
  *   - `entries(2).addr` type-checks and yields a `UInt` (R14).
  *   - `decode(waddr, 4)` produces a one-hot `Vec[Bool]` driving a combinational
  *     read via `Mux` (R14.3). */
case class Req(w: Int) extends Bundle:
  val addr = UInt(w)
  val prot = Bits(3)

class RegisterFile extends Module:
  val clk   = In(Clock())
  val rst   = In(Reset(ActiveHigh))
  given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)

  val req   = In(Req(8))              // bundle at port → req_addr, req_prot
  val waddr = In(UInt(2))             // runtime write index (0..3)
  val rd    = Out(Vec(4, Req(8)))     // Vec-of-bundle at port → rd_0_addr, …

  // internal Vec of Reg → struct-of-arrays: entries_addr [0:3]
  val entries = Reg(Vec(4, Req(8)), init = null)

  // runtime-index write on a Reg Vec — legal (R14.2). The whole-bundle write
  // assigns each leaf: entries_addr[waddr] <= req_addr; etc.
  entries(waddr).addr := req.addr
  entries(waddr).prot := req.prot

  // entries(2).addr type-checks as UInt (R14) — a constant probe output
  val probe = Out(UInt(8))
  probe := entries(2).addr

  // combinational read via decode one-hot + Mux (R14.3). R21: the arms agree
  // in type as well as width, so the `Bits` field's zero arm is written as an
  // explicit reinterpretation — `.asBits` moves no bit and emits nothing, so
  // the Verilog is the same `3'd0` either way (R21.4/R21.6).
  val sel = decode(waddr, 4)
  for j <- 0 until 4 do
    rd(j).addr := Mux(sel(j), entries(j).addr, 0.U(8))
    rd(j).prot := Mux(sel(j), entries(j).prot, 0.U(3).asBits)
