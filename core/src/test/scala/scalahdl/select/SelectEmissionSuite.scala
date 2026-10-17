package scalahdl.select

import scalahdl.dsl.{*, given}
import scalahdl.elab.Module
import scalahdl.emit.Emission
import scalahdl.switchcase.SwitchFixtures.*

/** R26 — the emitted Verilog is `case (1'b1)` with the conditions as labels.
  *
  * This is the requirement's whole reason for existing: the alternative is a
  * `when` chain, which means the same thing and reads as a hundred nested
  * `else`s, or a hand-built one-hot bus and an OR-tree encoder, which is what
  * the design that asked for R26 had. */
class SelectEmissionSuite extends munit.FunSuite:

  private def emit(m: => Module): String =
    new Emission("0.0.0-TEST").emit(m.elaborateDesign()).files.head._2

  private class Pla extends Fix:
    val opcode = In(UInt(16))
    val shape = Out(UInt(4))
    shape := 0.U(4)
    parallelCase {
      on((opcode & 0xF1F0.U(16)) === 0x7000.U(16)) { shape := 1.U(4) }
      on((opcode & 0xFFC0.U(16)) === 0x4EC0.U(16)) { shape := 2.U(4) }
      default { shape := 0.U(4) }
    }

  test("a cube list emits as case (1'b1), one line per cube") {
    val v = emit(new Pla)
    assert(v.contains("case (1'b1)"), v)
    // Literal radix is the emitter's own convention, not R26's; the shape
    // under test is the label, the colon and the one statement.
    assert(v.contains("((opcode & 16'd61936) == 16'd28672): shape = 4'd1;"), v)
    assert(v.contains("((opcode & 16'd65472) == 16'd20160): shape = 4'd2;"), v)
    assert(v.contains("default: shape = 4'd0;"), v)
    assert(v.contains("endcase"), v)
    // The thing R26 exists to avoid: no encoder, no one-hot bus, no chain.
    assert(!v.contains("else if"), v)
  }

  test("R26.1 — a parallelCase carries the claim R26 decided into the Verilog") {
    val v = emit(new Pla)
    // The attribute goes on the `case` line and nowhere else, because a
    // synthesiser has no access to the elaborator's proof and would otherwise
    // build the priority chain the language defines. What makes it sound is
    // R26's refusal to accept an arm it cannot decide.
    assert(v.contains("case (1'b1) // synopsys parallel_case"), v)
    // `full_case` is a different claim — that the arms are exhaustive — and
    // R26 decides nothing of the kind: conditions have no value space to
    // cover, which is why a `default` is what makes one of these complete.
    assert(!v.contains("full_case"), v)
  }

  test("R26.1 — a priorityCase claims nothing, so it carries nothing") {
    class Chain extends Fix:
      val a = In(UInt(4))
      val out = Out(UInt(8))
      out := 0.U(8)
      priorityCase {
        on(a === 1.U(4)) { out := 7.U(8) }
        on(a(0)) { out := 9.U(8) }
        default { out := 0.U(8) }
      }
    val v = emit(new Chain)
    assert(v.contains("case (1'b1)"), v)
    assert(!v.contains("parallel_case"), v)
  }

  test("several conditions on one arm are comma-separated labels") {
    class Aliased extends Fix:
      val a = In(UInt(4))
      val out = Out(UInt(8))
      out := 0.U(8)
      priorityCase {
        on(a === 1.U(4), a === 2.U(4)) { out := 7.U(8) }
        default { out := 0.U(8) }
      }
    val v = emit(new Aliased)
    assert(v.contains("(a == 4'd1), (a == 4'd2): out = 8'd7;"), v)
  }

  test("R22.1 prunes a projection's empty arms here too, and restores their coverage") {
    class Two extends Fix:
      val a = In(UInt(4))
      val x = Out(UInt(8))
      val y = Out(UInt(8))
      x := 0.U(8)
      y := 0.U(8)
      priorityCase {
        on(a === 1.U(4)) { x := 1.U(8) }
        on(a === 2.U(4)) { y := 2.U(8) }
        default { }
      }
    val v = emit(new Two)
    // `x`'s projection keeps its own arm and drops `y`'s, exactly as it would
    // for a switch — R26 inherits this by being one.
    val xBlock = v.linesIterator.dropWhile(!_.contains("x =")).take(4).mkString("\n")
    assert(v.contains("(a == 4'd1): x = 8'd1;"), v)
    assert(!v.contains("(a == 4'd2): x ="), v)
    assert(v.contains("(a == 4'd2): y = 8'd2;"), v)
  }
