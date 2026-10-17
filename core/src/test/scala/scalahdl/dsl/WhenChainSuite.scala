package scalahdl.dslsuite

import scalahdl.dsl.{*, given}
import scalahdl.elab.Module
import scalahdl.clock.ResetKind
import scalahdl.core.*
import scalahdl.core.ResetPolarity.ActiveHigh
import scalahdl.emit.Emission

/** R12 — `elsewhen` and `otherwise` build one nested conditional.
  *
  * These are regression tests for a defect M14 found while extending the same
  * scope machinery: `when` closed its frame before returning the `WhenBlock`,
  * so an else body was recorded in the *enclosing* scope. The emitted Verilog
  * was `if (c) y = a; y = b;` — an unconditional overwrite — and R11 read the
  * stray assignment as a default, so completeness passed as well. The chain
  * now reopens, and both halves are asserted here: the IR nests, and the
  * emitted text has one `else` per link. */
class WhenChainSuite extends munit.FunSuite:

  abstract class Fix extends Module:
    val clk = In(Clock())
    val rst = In(Reset(ActiveHigh))
    given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)

  private def emit(m: => Module): String =
    new Emission("0.0.0-TEST").emit(m.elaborateDesign()).files.head._2

  private def driver(m: Module, name: String): List[Stmt] =
    val top = m.elaborateDesign().top
    val id = top.signals.find(_.name == name).map(_.id).getOrElse(fail(s"no signal $name"))
    top.drivers.find(_.signalId == id).map(_.stmts).getOrElse(fail(s"no driver for $name"))

  class Pair extends Fix:
    val sel = In(Bool())
    val a = In(UInt(8))
    val b = In(UInt(8))
    val y = Out(UInt(8))
    when(sel) { y := a }
    .otherwise { y := b }

  class Chain extends Fix:
    val sel = In(Bool())
    val sel2 = In(Bool())
    val a = In(UInt(8))
    val b = In(UInt(8))
    val y = Out(UInt(8))
    when(sel) { y := a }
    .elsewhen(sel2) { y := b }
    .otherwise { y := 0.U(8) }

  test("`otherwise` fills the else arm, and nothing follows the conditional"):
    driver(new Pair, "y") match
      case Stmt.When(_, thenStmts, elseStmts, _) :: Nil =>
        assertEquals(thenStmts.length, 1)
        assertEquals(elseStmts.length, 1, "the else body is the conditional's, not a sibling")
      case other => fail(s"expected one `When` and nothing else, got $other")

  test("`otherwise` emits an `else`, not a trailing assignment"):
    val verilog = emit(new Pair)
    assert(verilog.contains("end else begin\n      y = b;\n    end"), verilog)
    assertEquals(verilog.linesIterator.count(_.trim == "y = b;"), 1,
      "the else body must not also appear unconditionally")

  test("`elsewhen` nests inside the previous else arm"):
    driver(new Chain, "y") match
      case Stmt.When(_, _, (inner: Stmt.When) :: Nil, _) :: Nil =>
        assertEquals(inner.thenStmts.length, 1)
        assertEquals(inner.elseStmts.length, 1, "`otherwise` closes the innermost link")
      case other => fail(s"expected a nested chain, got $other")

  test("an `elsewhen` chain emits one `else` per link"):
    val verilog = emit(new Chain)
    assertEquals(verilog.linesIterator.count(_.contains("else")), 2, verilog)
    assert(verilog.contains("end else if (sel2) begin\n      y = b;\n    end else begin"), verilog)

  test("a chain covers the wire through its else arm, not through a stray default"):
    // Completeness must pass for the right reason: there is no unconditional
    // assignment in the block to mistake for a default (R11).
    val stmts = driver(new Pair, "y")
    assert(!stmts.exists(_.isInstanceOf[Stmt.Assign]), stmts.toString)

  test("a chain that leaves a path open still fails R11"):
    class Open extends Fix:
      val sel = In(Bool())
      val sel2 = In(Bool())
      val a = In(UInt(8))
      val y = Out(UInt(8))
      when(sel) { y := a }
      .elsewhen(sel2) { y := 0.U(8) }
    val err = intercept[scalahdl.diag.ValidationFailed] { (new Open).elaborateDesign() }
    assert(err.diagnostics.exists(_.code == scalahdl.diag.Code.COMBINATIONAL_INCOMPLETE),
      err.diagnostics.map(_.summary).toString)

  test("a `when` kept in a `val` cannot attach an else across other statements"):
    class Detached extends Fix:
      val sel = In(Bool())
      val a = In(UInt(8))
      val y = Out(UInt(8))
      val z = Out(UInt(8))
      val block = when(sel) { y := a }
      z := 0.U(8)
      block.otherwise { y := 0.U(8) }
    intercept[scalahdl.elab.Builder.ElabError] { (new Detached).elaborateDesign() }

  test("chains nest: an inner `when` does not consume the outer one's else"):
    // The `when` inside a `when` is the shape the fetch FSM's request state
    // has, and it is how the chain bookkeeping learned to be a stack.
    class Nested extends Fix:
      val a = In(Bool())
      val b = In(Bool())
      val y = Out(UInt(8))
      when(a) {
        y := 1.U(8)
        when(b) { y := 2.U(8) }
      }.otherwise {
        y := 3.U(8)
      }
    driver(new Nested, "y") match
      case Stmt.When(_, outerThen, outerElse, _) :: Nil =>
        assertEquals(outerThen.length, 2, "the inner conditional stays in the then arm")
        assert(outerThen(1).isInstanceOf[Stmt.When])
        assertEquals(outerElse.length, 1, "the else arm belongs to the outer `when`")
      case other => fail(s"expected one nested conditional, got $other")

  test("a chain inside a `switch` alternative stays inside it"):
    class Mixed extends Fix:
      val sel = In(UInt(1))
      val go = In(Bool())
      val y = Out(UInt(8))
      switch(sel) {
        is(0.U(1)) {
          when(go) { y := 1.U(8) }.otherwise { y := 2.U(8) }
        }
        is(1.U(1)) { y := 3.U(8) }
      }
    val stmts = driver(new Mixed, "y")
    stmts match
      case (sw: Stmt.Switch) :: Nil =>
        sw.cases.head.stmts match
          case Stmt.When(_, t, e, _) :: Nil =>
            assertEquals(t.length, 1)
            assertEquals(e.length, 1)
          case other => fail(s"expected one conditional in the alternative, got $other")
      case other => fail(s"expected one switch, got $other")

  /** R12 — a nested conditional keeps its nesting through the `else`.
    *
    * The second defect in the same place, and the more dangerous one: the
    * emitter wrote a lone `when` bare in the then-position of a chain, so
    *
    *     if (a) if (c) y = p;
    *     else y = q;
    *
    * came back out of Verilog's dangling-else rule with the `else` bound to
    * the *inner* `if` — `y = q` on `a && !c`, and nothing at all when `!a`.
    * Found in plustoo-soc's m68k core, where it silently stopped every taken
    * branch from re-aiming the instruction stream. Every branch is now a
    * block (R12.2), so the `else` follows the outer `if`'s `end` and there is
    * no other `if` for it to bind to.
    */
  class Dangling extends Fix:
    val a = In(Bool())
    val c = In(Bool())
    val p = In(UInt(8))
    val q = In(UInt(8))
    val y = Out(UInt(8))
    y := 0
    when(a) { when(c) { y := p } }
    .otherwise { y := q }

  class DanglingChain extends Fix:
    val a = In(Bool())
    val b = In(Bool())
    val c = In(Bool())
    val p = In(UInt(8))
    val q = In(UInt(8))
    val y = Out(UInt(8))
    y := 0
    // The then-arm's own chain ends in an `elsewhen` with no `otherwise`, so
    // it is still open when the outer `else` arrives.
    when(a) { when(b) { y := p }.elsewhen(c) { y := q } }
    .otherwise { y := 0.U(8) }

  test("a lone nested `when` is closed before the outer `else`"):
    val verilog = emit(new Dangling)
    assert(verilog.contains(
      """    if (a) begin
        |      if (c) begin
        |        y = p;
        |      end
        |    end else begin
        |      y = q;
        |    end""".stripMargin), verilog)

  test("an open chain in the then-arm is braced too"):
    val verilog = emit(new DanglingChain)
    assert(verilog.linesIterator.map(_.trim).exists(_.startsWith("if (a) begin")), verilog)

  test("a conditional that closes itself is braced like any other (R12.2)"):
    // Every branch is a block, not only the ones an `else` could be misread
    // against: the rule has no case to decide, so it cannot decide wrong.
    class Closed extends Fix:
      val a = In(Bool())
      val c = In(Bool())
      val p = In(UInt(8))
      val q = In(UInt(8))
      val y = Out(UInt(8))
      when(a) { when(c) { y := p }.otherwise { y := q } }
      .otherwise { y := 0.U(8) }
    val verilog = emit(new Closed)
    assert(verilog.contains(
      """    if (a) begin
        |      if (c) begin
        |        y = p;
        |      end else begin
        |        y = q;
        |      end
        |    end else begin
        |      y = 8'd0;
        |    end""".stripMargin), verilog)
