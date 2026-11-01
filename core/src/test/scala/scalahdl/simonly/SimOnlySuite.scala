package scalahdl.simonly

import scalahdl.clock.ResetKind
import scalahdl.core.*
import scalahdl.core.ResetPolarity.{ActiveHigh, ActiveLow}
import scalahdl.diag.{Code, Diagnostic, ValidationFailed}
import scalahdl.dsl.{*, given}
import scalahdl.elab.Module
import scalahdl.emit.Emission

/** R27 — a block that exists only in simulation.
  *
  * Two halves, and the second is the one that makes the first safe: the block
  * must reach the emitted Verilog inside its guard, and *nothing inside it may
  * become part of the design*. A `simOnly` that could assign would put a driver
  * where nobody can see it and make a `translate_off` region load-bearing —
  * and the resulting divergence between simulation and hardware would be
  * invisible to simulation, which is the only thing that would look. */
class SimOnlySuite extends munit.FunSuite:

  abstract class Fix extends Module:
    val clk = In(Clock())
    val rst = In(Reset(ActiveHigh))
    given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)

  private def emitted(m: Module): String =
    new Emission("0.1.1").emit(m.elaborateDesign()).files.head._2

  private def refused(code: Code)(body: => Module): Diagnostic =
    val failure = intercept[ValidationFailed] { body.elaborateDesign() }
    failure.diagnostics.find(_.code == code).getOrElse(
      fail(s"expected $code, got ${failure.diagnostics.map(_.render)}"))

  /** The guarded region of an emitted module, guard lines included. */
  private def guarded(v: String): List[String] =
    val lines = v.linesIterator.toList
    val from = lines.indexWhere(_.contains("translate_off"))
    val to = lines.indexWhere(_.contains("translate_on"))
    if from < 0 || to < 0 then Nil else lines.slice(from, to + 1)

  // ------------------------------------------------------- what it emits

  test("a check is a block of its own, and drives nothing"):
    class Claim extends Fix:
      val d = In(UInt(8))
      val q = Out(UInt(8))
      q := d
      simOnly { check(q === d, "the output is not the input") }

    val module = (new Claim).elaborate()
    // It is not a driver block, and that is the point: every consumer of
    // `drivers` is asking where a signal's value comes from.
    assertEquals(module.checks.length, 1)
    assertEquals(module.drivers.length, 1)
    assertEquals(module.checks.head.stmts.length, 1)
    assert(module.checks.head.stmts.head.isInstanceOf[Stmt.Check])
    // The block's synthetic id names no signal, so nothing declares it.
    assert(!module.signals.exists(_.id == module.checks.head.id))

  test("it emits inside the pragma, on the domain's clock"):
    class Claim extends Fix:
      val d = In(UInt(8))
      val q = Out(UInt(8))
      q := d
      simOnly { check(q === d, "the output is not the input") }

    val region = guarded(emitted(new Claim))
    assertEquals(region.head.trim, "// synthesis translate_off")
    assertEquals(region.last.trim, "// synthesis translate_on")
    assert(region.exists(_.contains("always @(posedge clk)")), region.mkString("\n"))
    assert(region.exists(_.contains("$stop;")), region.mkString("\n"))
    // The message carries the module and the time: one component instantiated
    // twice emits this line twice, and `%0t` is what tells the copies apart.
    assert(region.exists(_.contains(
      """$display("[%0t] Claim: the output is not the input", $time);""")),
      region.mkString("\n"))

  test("a design with a check is bit-identical to the same design without one"):
    class Plain extends Fix:
      val d = In(UInt(8))
      val q = Out(UInt(8))
      q := d
    class Claimed extends Fix:
      val d = In(UInt(8))
      val q = Out(UInt(8))
      q := d
      simOnly { check(q === d, "the output is not the input") }

    // This is the exit criterion, stated as a test: delete what the pragma
    // wraps — which is what a synthesiser does — and the two files agree line
    // for line, module header included. No extra net, no `reg` that was a
    // `wire`, no reordering.
    def circuit(v: String): List[String] =
      val region = guarded(v).toSet
      v.linesIterator.filterNot(region.contains)
        // The module's own name and the generator that produced it differ
        // because these are two classes; everything the two files say about
        // *hardware* has to be identical, which is what is compared.
        .filterNot(_.startsWith("// Generator:"))
        .map(_.replace("Claimed", "Plain"))
        .toList
    assertEquals(circuit(emitted(new Claimed)), circuit(emitted(new Plain)))

  test("the reset guard is the domain's, and a domain with no reset gets none"):
    class SyncHigh extends Module:
      val clk = In(Clock())
      val rst = In(Reset(ActiveHigh))
      given d: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)
      val q = Out(Bool()); q := True
      simOnly { check(q, "q is low") }
    class AsyncLow extends Module:
      val clk = In(Clock())
      val rst = In(Reset(ActiveLow))
      given d: ClockDomain = ClockDomain(clk, rst, ResetKind.Async, ActiveLow)
      val q = Out(Bool()); q := True
      simOnly { check(q, "q is low") }
    class NoReset extends Module:
      val clk = In(Clock())
      given d: ClockDomain = ClockDomain(clk, ResetKind.None)
      val q = Out(Bool()); q := True
      simOnly { check(q, "q is low") }

    // A check is a claim about a *running* design, so an edge taken while
    // reset is asserted is not one it answers for. This is what a concurrent
    // assertion spells `disable iff (reset)`; here it comes from the domain,
    // so no assertion restates it (R13).
    assert(guarded(emitted(new SyncHigh)).exists(_.contains("if (!rst) begin")),
      guarded(emitted(new SyncHigh)).mkString("\n"))
    assert(guarded(emitted(new AsyncLow)).exists(_.contains("if (rst) begin")),
      guarded(emitted(new AsyncLow)).mkString("\n"))
    // Nothing to be out of, so nothing to guard on.
    assert(!guarded(emitted(new NoReset)).exists(_.contains("if (rst")),
      guarded(emitted(new NoReset)).mkString("\n"))
    // ...and the sensitivity list is still the clock's alone: a check is
    // sampled on an edge, never on the reset.
    assert(guarded(emitted(new AsyncLow)).exists(_.contains("always @(posedge clk) begin")),
      guarded(emitted(new AsyncLow)).mkString("\n"))

  test("a check takes the condition nest it was written under (R12)"):
    class Nested extends Fix:
      val armed = In(Bool())
      val d = In(UInt(8))
      val q = Out(UInt(8))
      q := d
      when(armed) {
        simOnly { check(q === d, "the output is not the input") }
      }

    val module = (new Nested).elaborate()
    module.checks.head.stmts.head match
      case Stmt.When(_, thenStmts, elseStmts, _) =>
        assertEquals(elseStmts, Nil)
        assert(thenStmts.head.isInstanceOf[Stmt.Check])
      case other => fail(s"the `when` did not reach the check: $other")
    assert(guarded(emitted(new Nested)).exists(_.contains("if (armed)")),
      guarded(emitted(new Nested)).mkString("\n"))

  test("two blocks on one clock are one block, in source order"):
    class Two extends Fix:
      val q = Out(Bool())
      q := True
      simOnly { check(q, "first") }
      simOnly { check(q, "second") }

    val module = (new Two).elaborate()
    assertEquals(module.checks.length, 1)
    assertEquals(module.checks.head.stmts.collect {
      case Stmt.Check(_, _, message, _) => message
    }, List("first", "second"))
    // One `always`, because two would be two blocks a reader could not tell
    // apart in the output.
    assertEquals(guarded(emitted(new Two)).count(_.contains("always @")), 1)

  test("a literal in a condition takes its width from what it is compared against"):
    class Widened extends Fix:
      val count = In(UInt(10))
      val q = Out(Bool())
      q := True
      simOnly { check(count =/= 43, "the count reached 43") }

    // R6.1 through the same resolver a driver block's statements use. Left
    // unresolved the check would compare against a differently-sized 43 and
    // pass for the wrong reason, which is worse than having no check.
    assert(guarded(emitted(new Widened)).exists(_.contains("10'd43")),
      guarded(emitted(new Widened)).mkString("\n"))

  // --------------------------------------------- what it refuses to become

  test("an assignment inside is refused, and the block is named"):
    val error = refused(Code.SIM_ONLY_DRIVES) {
      new Fix:
        val d = In(UInt(8))
        val q = Out(UInt(8))
        simOnly { q := d }
    }
    assert(error.summary.contains("`q` is assigned"), error.summary)
    assert(error.related.exists(_.note.contains("bit-identical")),
      s"the reason belongs in the diagnostic: ${error.related.map(_.note)}")
    assert(error.fix.exists(_.contains("outside")), error.fix.toString)

  test("a declaration inside is refused: it would emit a net outside the guard"):
    val error = refused(Code.SIM_ONLY_DRIVES) {
      new Fix:
        val q = Out(Bool())
        q := True
        simOnly {
          val spy = Wire(Bool())
          check(spy, "never reached")
        }
    }
    assert(error.summary.contains("`spy` is declared"), error.summary)

  test("a register inside is refused too, on the same rule"):
    val error = refused(Code.SIM_ONLY_DRIVES) {
      new Fix:
        val q = Out(Bool())
        q := True
        simOnly {
          val seen = Reg(Bool(), init = False)
          check(seen, "never reached")
        }
    }
    assert(error.summary.contains("`seen` is declared"), error.summary)

  test("a memory write inside is refused"):
    val error = refused(Code.SIM_ONLY_DRIVES) {
      new Fix:
        val addr = In(UInt(4))
        val data = In(UInt(8))
        val q = Out(Bool())
        q := True
        val log = Registers(16, UInt(8))
        simOnly { log.write(addr, data) }
    }
    assert(error.summary.contains("is written"), error.summary)

  test("a check outside `simOnly` is refused, because nothing would guard it"):
    val error = refused(Code.CHECK_OUTSIDE_SIM_ONLY) {
      new Fix:
        val q = Out(Bool())
        q := True
        check(q, "unguarded")
    }
    assert(error.fix.exists(_.contains("simOnly")), error.fix.toString)

  // --------------------------------- what it must not change about the design

  test("a check adds no combinational edge and no depth"):
    class Plain extends Fix:
      val a = In(UInt(8))
      val b = In(UInt(8))
      val q = Out(UInt(8))
      q := a + b
    class Claimed extends Fix:
      val a = In(UInt(8))
      val b = In(UInt(8))
      val q = Out(UInt(8))
      q := a + b
      simOnly { check(q =/= 0, "the sum is zero") }

    // R28 and R29 read this graph. An edge from a check would let a claim
    // invent a cycle no hardware has, or lengthen a path no signal travels —
    // the design must not change because something said something about it.
    val plain = CombGraph.build((new Plain).elaborate())
    val claimed = CombGraph.build((new Claimed).elaborate())
    assertEquals(claimed.nodes.map(claimed.render).toSet, plain.nodes.map(plain.render).toSet)
