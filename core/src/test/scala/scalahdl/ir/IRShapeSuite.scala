package scalahdl.ir

import scalahdl.dsl.{*, given}
import scalahdl.elab.Module
import scalahdl.clock.ResetKind
import scalahdl.core.*
import scalahdl.core.ResetPolarity.ActiveHigh
import scalahdl.emit.VerilogEmitter

/** R17 — The IR: one representation, acyclic, immutable after elaboration.
  * Elaborating the Counter yields a `Design` with one `Module`, ports,
  * signals, and driver blocks matching the source. No node references its
  * driver (R17.1); the emitter reads the IR without mutating it. */
class IRShapeSuite extends munit.FunSuite:

  class Fix extends Module:
    val clk = In(Clock())
    val rst = In(Reset(ActiveHigh))
    val en = In(Bool())
    val count = Out(UInt(8))
    given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)
    val cnt = Reg(UInt(8), init = 0.U(8))
    when(en) { cnt := cnt + 1.U(8) }
    count := cnt

  test("elaborating yields a Design with one Module"):
    val design = (new Fix).elaborateDesign()
    assertEquals(design.modules.length, 1)
    assertEquals(design.top.name, "Fix")

  test("ports appear in declaration order with exact names"):
    val design = (new Fix).elaborateDesign()
    val ports = design.top.ports
    assertEquals(ports.map(_.name), List("clk", "rst", "en", "count"))
    assertEquals(ports.map(_.direction), List(Direction.In, Direction.In, Direction.In, Direction.Out))

  test("signals include cnt (register) with init"):
    val design = (new Fix).elaborateDesign()
    val cnt = design.top.signals.find(_.name == "cnt").getOrElse(fail("no cnt signal"))
    assert(cnt.isReg, "cnt should be a register")
    assert(cnt.init.isDefined, "cnt should have an init value")

  test("one driver block per driven signal (R12)"):
    val design = (new Fix).elaborateDesign()
    // cnt and count are driven; clk/rst/en are not.
    val drivenNames = design.top.drivers.map(db =>
      design.top.signals.find(_.id == db.signalId).map(_.name).getOrElse("?"))
    assert(drivenNames.contains("cnt"), s"cnt should be driven: $drivenNames")
    assert(drivenNames.contains("count"), s"count should be driven: $drivenNames")
    // each driven signal appears exactly once (one block per signal)
    assertEquals(drivenNames.toSet.size, drivenNames.length, "duplicate driver blocks")

  test("no signal references its driver (R17.1 — acyclic)"):
    // DriverBlock references its target signal by id; Signal carries no
    // driver reference. Verify structurally: Signal has no field pointing
    // back to a DriverBlock (it has id/name/type/kind/init/loc only).
    val design = (new Fix).elaborateDesign()
    // The IR case classes have no driver field on Signal; this is a structural
    // property of the ADT. Confirm DriverBlock holds the signalId, not vice versa.
    design.top.drivers.foreach { db =>
      // DriverBlock holds the signalId (block → signal); the signal holds none.
      assert(db.stmts != null, "driver block statement list must exist")
    }
    // Signals reference nothing about drivers. Asserted by field name rather
    // than by count, so the guard says what it is guarding: R24's `encoding` is
    // diagnostic metadata and R13.8's `powerUp` is a value of this signal, and
    // joining them is fine; a `driver` field is not.
    design.top.signals.foreach { s =>
      assertEquals(s.productElementNames.toList,
        List("id", "name", "typeOf", "kind", "init", "loc", "powerUp", "domain", "encoding"),
        "Signal must not gain a back-reference to its driver (R17.1)")
    }

  test("emitter does not mutate the IR (hash before/after unchanged)"):
    val design = (new Fix).elaborateDesign()
    val before = design.hashCode()
    val emitter = new VerilogEmitter("0.1.0-SNAPSHOT")
    emitter.emitModule(design.top, "Fix")
    emitter.emitModule(design.top, "Fix")
    val after = design.hashCode()
    assertEquals(after, before, "IR hash must be unchanged by emission")

  test("a conditional that writes multiple registers is partitioned by target"):
    class MultiTarget extends Fix:
      val other = Reg(UInt(8), init = 0.U(8))
      when(en) {
        cnt := cnt + 1.U(8)
        other := count
      }
    val design = (new MultiTarget).elaborateDesign()
    val byName = design.top.signals.map(s => s.id -> s.name).toMap
    val blocks = design.top.drivers.map(db => byName(db.signalId) -> db).toMap

    List("cnt", "other").foreach { name =>
      val block = blocks(name)
      assertEquals(block.stmts.flatMap(assignedRoots).distinct, List(block.signalId),
        s"the $name driver must not contain assignments to another register")
    }

  test("a switch that writes multiple registers is partitioned by target"):
    // R22 partitions like R12 does, with one difference the projection must
    // not smooth over: the *alternatives* survive whole, so each block still
    // shows every state, and only the statements are filtered.
    class MultiTarget extends Fix:
      val sel = In(UInt(2))
      val other = Reg(UInt(8), init = 0.U(8))
      switch(sel) {
        is(0.U(2)) { cnt := cnt + 1.U(8) }
        is(1.U(2)) { other := count }
      }
    val design = (new MultiTarget).elaborateDesign()
    val byName = design.top.signals.map(s => s.id -> s.name).toMap
    val blocks = design.top.drivers.map(db => byName(db.signalId) -> db).toMap

    List("cnt", "other").foreach { name =>
      val block = blocks(name)
      assertEquals(block.stmts.flatMap(assignedRoots).distinct, List(block.signalId),
        s"the $name driver must not contain assignments to another register")
      block.stmts.collect { case s: Stmt.Switch => s } match
        case one :: Nil => assertEquals(one.cases.length, 2,
          s"the $name projection keeps both alternatives")
        case other => fail(s"expected one switch in the $name driver, got $other")
    }

  test("the emitter does not mutate a switch either"):
    class Fsm extends Fix:
      val sel = In(UInt(2))
      switch(sel) {
        is(0.U(2)) { cnt := 0.U(8) }
        is(1.U(2), 2.U(2)) { cnt := cnt + 1.U(8) }
      }
    val design = (new Fsm).elaborateDesign()
    val before = design.hashCode()
    val emitter = new VerilogEmitter("0.1.0-SNAPSHOT")
    emitter.emitModule(design.top, "Fsm")
    emitter.emitModule(design.top, "Fsm")
    assertEquals(design.hashCode(), before, "IR hash must be unchanged by emission")

  /** Every root a statement tree assigns, at any depth. */
  private def assignedRoots(stmt: Stmt): List[SignalId] = stmt match
    case Stmt.Assign(lhs, _, _, _) => List(lhs.rootId)
    case Stmt.When(_, thenStmts, elseStmts, _) =>
      (thenStmts ++ elseStmts).flatMap(assignedRoots)
    case Stmt.Switch(_, _, cases, defaults, _) =>
      (cases.flatMap(_.stmts) ++ defaults.getOrElse(Nil)).flatMap(assignedRoots)
    case Stmt.MemWrite(id, _, _, _) => List(id)
    // R27 — a check assigns nothing, which is the whole of what it is. Its
    // block id is not a root: it names no signal.
    case Stmt.Check(_, _, _, _) => Nil
