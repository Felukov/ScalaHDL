package scalahdl.fsm

import scalahdl.dsl.{*, given}
import scalahdl.elab.Module
import scalahdl.core.*
import scalahdl.emit.Emission

/** R25 — the captured graph: what a `goto` records, and the report drawn from
  * it.
  *
  * The guard is the part worth testing hardest. It is not something the source
  * states — it is read off the `when` scopes open at the `goto`, which is the
  * same structure that produced the hardware, so a nesting or a negation the
  * capture gets wrong would put an edge in the documentation that the silicon
  * does not have. */
class FsmGraphSuite extends munit.FunSuite:
  import FsmFixtures.*
  import FsmGraphFixtures.*

  test("an unconditional transition records no guard"):
    val g = new Guarded
    assertEquals(edge(fsmOf(g), Step.A, Step.B).guard, None)

  test("a `when` records its condition"):
    val g = new Guarded
    assertEquals(edge(fsmOf(g), Step.B, Step.C).guard, Some(g.ref("p")))

  test("nested `when`s record the conjunction, outermost first"):
    val g = new Guarded
    assertEquals(edge(fsmOf(g), Step.C, Step.D).guard,
      Some(Expr.BinOp("&", g.ref("p"), g.ref("q"))))

  test("an `otherwise` records the negation"):
    val o = new Otherwise
    assertEquals(edge(fsmOf(o), Step.A, Step.C).guard, Some(Expr.UnOp("!", o.ref("p"))))

  test("an `elsewhen` records the new condition under the earlier negation"):
    val g = new Guarded
    assertEquals(edge(fsmOf(g), Step.D, Step.B).guard,
      Some(Expr.BinOp("&", Expr.UnOp("!", g.ref("p")), g.ref("q"))))

  test("the `otherwise` of an else-if chain carries every earlier negation"):
    // `!p & !q`, which is what the emitted `else` actually means — the guard is
    // read off the same nesting R12 built, so it cannot disagree with it.
    val g = new Guarded
    assertEquals(edge(fsmOf(g), Step.D, Step.A).guard,
      Some(Expr.BinOp("&", Expr.UnOp("!", g.ref("p")), Expr.UnOp("!", g.ref("q")))))

  test("a guard's literals are resolved, so the label and the `if` read alike"):
    // R6.4's bare `Int` has no width until the freeze resolves it against its
    // sibling, and a transition's guard is a copy of the condition taken at
    // the `goto` rather than a share of the statement tree — so before the
    // guards were resolved too, an edge read `(count == 1'b43)` where the
    // emitted `if` said `(count == 10'd43)`. Found porting a floppy GCR
    // codec, whose every field boundary is a bare `count === n`.
    val m = new BareLiteralGuard
    val guard = fsmOf(m).transitions.find(t => t.from.contains(BigInt(0)))
      .flatMap(_.guard).getOrElse(fail("no guard on the first edge"))
    assertEquals(guard, Expr.BinOp("==", m.ref("count"), Expr.uint(43, 10)),
      "the literal carries the counter's width, not its own minimal one")
    assert(fsmReport(new BareLiteralGuard).contains("count == 10'd43"),
      fsmReport(new BareLiteralGuard))

  test("a transition in the `default` arm is recorded from the unlisted values"):
    val fsm = fsmOf(new WithDefault)
    val fromDefault = fsm.transitions.filter(_.from.isEmpty)
    assertEquals(fromDefault.length, 1)
    assertEquals(fromDefault.head.to, BigInt(2), "the default arm's `goto` target")

  test("transitions keep elaboration order (R20.1)"):
    val fsm = fsmOf(new Guarded)
    // A→B, B→C, C→D, then D's chain in the order it was written: the `stay`
    // self-loop, the `elsewhen` edge, and the `otherwise` edge.
    assertEquals(fsm.transitions.map(t => t.from.map(_.toInt) -> t.to.toInt),
      List(Some(0) -> 1, Some(1) -> 2, Some(2) -> 3, Some(3) -> 3, Some(3) -> 1, Some(3) -> 0))

  test("the states carry their names, codes, and which are terminal"):
    val fsm = fsmOf(new Machine)
    assertEquals(fsm.states.map(_.code.toInt), List(0, 1, 2))
    assertEquals(fsm.states.map(_.terminal), List(false, false, true))
    assertEquals(fsm.terminalStates.map(_.name), List("Light.Broken"))

  // ---- the report --------------------------------------------------------

  test("the report is emitted only for modules that declare a machine"):
    val withFsm = new Emission().emit((new Machine).elaborateDesign()).reports.map(_._1)
    assert(withFsm.exists(_.endsWith(".fsm.md")), withFsm.toString)
    val without = new Emission().emit((new NoMachine).elaborateDesign()).reports.map(_._1)
    assert(!without.exists(_.endsWith(".fsm.md")),
      s"a module with no machine gets no FSM report: $without")

  test("the report draws the machine, its initial state, and its terminals"):
    val report = fsmReport(new Machine)
    assert(report.contains("# FSM report: Machine"), report)
    assert(report.contains("## state — Light, 2 bits, initial Off"), report)
    assert(report.contains("stateDiagram-v2"), report)
    assert(report.contains("[*] --> Off"), report)
    assert(report.contains("Off --> On : press"), report)
    assert(report.contains("On --> Broken : fault"), report)
    assert(report.contains("Terminal: Broken"), report)

  test("the transition table names the guard and the source line"):
    val report = fsmReport(new Machine)
    val row = report.linesIterator.find(_.startsWith("| On | Broken |")).getOrElse(
      fail(s"no table row for On -> Broken:\n$report"))
    assert(row.contains("`fault`"), row)
    assert(row.contains("FsmSurfaceSuite.scala:"), s"the row cites the `goto`'s own line: $row")

  test("a guard too wide for the diagram becomes a reference resolved in the table"):
    val report = fsmReport(new WideGuard)
    assert(report.contains("--> B : g1"), s"the diagram carries the reference:\n$report")
    assert(report.contains("`g1` = `"), s"the table resolves it:\n$report")

  test("a `|` in a guard is escaped so the table stays a table"):
    // Bitwise-or is in every guard that reads more than one condition, and an
    // unescaped `|` ends the cell it is in.
    val report = fsmReport(new WideGuard)
    val rows = report.linesIterator.filter(_.startsWith("| A |")).toList
    assert(rows.nonEmpty, report)
    rows.foreach { row =>
      assertEquals(row.count(_ == '|') - row.sliding(2).count(_ == "\\|"), 5,
        s"a row has four cells and so five separators: $row")
    }

  test("the report is deterministic (R20.1)"):
    assertEquals(fsmReport(new Machine), fsmReport(new Machine))

object FsmGraphFixtures:
  import FsmFixtures.Fix

  object Step extends Encoding(2):
    val A, B, C, D = Value

  /** Every shape of guard R25 has to read off the scope stack. */
  class Guarded extends Fix:
    val p = In(Bool())
    val q = In(Bool())

    val state = Fsm(Step, init = Step.A) {
      in(Step.A) { goto(Step.B) }
      in(Step.B) {
        when(p) { goto(Step.C) }
      }
      in(Step.C) {
        when(p) {
          when(q) { goto(Step.D) }
        }
      }
      in(Step.D) {
        when(p) { stay() }
          .elsewhen(q) { goto(Step.B) }
          .otherwise { goto(Step.A) }
      }
    }

  /** A plain `when`/`otherwise` pair, so the negation is tested without an
    * else-if chain's additional terms. */
  class Otherwise extends Fix:
    val p = In(Bool())
    val state = Fsm(Step, init = Step.A) {
      in(Step.A) {
        when(p) { goto(Step.B) }.otherwise { goto(Step.C) }
      }
      in(Step.B) { goto(Step.D) }
      in(Step.C) { goto(Step.D) }
      in(Step.D) { goto(Step.A) }
    }

  class WithDefault extends Fix:
    val go = In(Bool())
    val state = Fsm(Step, init = Step.A) {
      in(Step.A) { goto(Step.B) }
      in(Step.B) { goto(Step.D) }
      in(Step.C) { goto(Step.A) }
      in(Step.D) { goto(Step.A) }
      // `Step` names all four values of its two bits, so this arm is
      // unreachable hardware — but it is the shape under test: an edge the
      // machine records from the values no arm names.
      default { goto(Step.C) }
    }

  class WideGuard extends Fix:
    val a, b, c, d, e = In(Bool())
    val state = Fsm(Step, init = Step.A) {
      in(Step.A) {
        when(a & b & c & d & e & (a | b) & (c | d)) { goto(Step.B) }
        stay()
      }
      in(Step.B) { goto(Step.C) }
      in(Step.C) { goto(Step.D) }
      in(Step.D) { goto(Step.A) }
    }

  /** Every guard a bare `Int` (R6.4), which is what a ported counter-driven
    * machine is written with. */
  class BareLiteralGuard extends Fix:
    val count = Reg(UInt(10), init = 0)
    count := count + 1
    val state = Fsm(Step, init = Step.A) {
      in(Step.A) { when(count === 43) { goto(Step.B) } }
      in(Step.B) { when(count === 682) { goto(Step.C) } }
      in(Step.C) { goto(Step.D) }
      in(Step.D) { goto(Step.A) }
    }

  class NoMachine extends Fix:
    val x = In(UInt(4))
    val y = Out(UInt(4))
    y := x + 1.U(4)

  def edge(fsm: FsmInfo, from: EncodingValue, to: EncodingValue): FsmTransition =
    fsm.transitions.find(t => t.from.contains(BigInt(from.code)) && t.to == BigInt(to.code))
      .getOrElse(throw AssertionError(s"no transition $from -> $to in ${fsm.transitions}"))

  extension (m: Module)
    /** The reference expression for one of this module's signals — what a
      * guard reads, so a captured guard is compared as the tree it is rather
      * than as text some printer produced. */
    def ref(name: String): Expr =
      Expr.Ref(m.elaborateDesign().top.signals.find(_.name == name)
        .getOrElse(throw AssertionError(s"no signal named $name")).id)

  def fsmReport(m: Module): String =
    new Emission().emit(m.elaborateDesign()).reports
      .find(_._1.endsWith(".fsm.md")).map(_._2)
      .getOrElse(throw AssertionError("no FSM report emitted"))
