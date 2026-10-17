package scalahdl.select

import scalahdl.core.*
import scalahdl.dsl.{*, given}
import scalahdl.elab.Module
import scalahdl.switchcase.SwitchFixtures.*

/** R26 — what `priorityCase` and `parallelCase` put in the IR.
  *
  * The claim under test is that they put in a `Switch`: the same statement R22
  * writes, with a selection kind and the constant one as its selector. That is
  * what makes R12's projection, R22.1's pruning and R11's coverage apply to a
  * construct they were never told about. */
class SelectSurfaceSuite extends munit.FunSuite:

  private class Priority extends Fix:
    val a = In(UInt(4))
    val b = In(UInt(4))
    val out = Out(UInt(8))
    out := 0.U(8)
    priorityCase {
      on(a === b) { out := 1.U(8) }
      on(a > b)   { out := 2.U(8) }
      default     { out := 3.U(8) }
    }

  private class Parallel extends Fix:
    val opcode = In(UInt(8))
    val shape = Out(UInt(4))
    shape := 0.U(4)
    parallelCase {
      on((opcode & 0xF0.U(8)) === 0x70.U(8)) { shape := 1.U(4) }
      on((opcode & 0xFC.U(8)) === 0x4C.U(8)) { shape := 2.U(4) }
      default { shape := 0.U(4) }
    }

  test("a priorityCase is a Switch on the constant one, selecting on conditions") {
    val s = onlySwitch(new Priority, "out")
    assertEquals(s.kind, SelectKind.Priority)
    assert(s.kind.onConditions)
    assertEquals(s.selector, Expr.Lit(BigInt(1), Some(1), Some(LitKind.Bool)))
    assertEquals(s.cases.length, 2)
    assert(s.defaultStmts.isDefined)
  }

  test("an arm's labels are its conditions, in the order written") {
    val s = onlySwitch(new Parallel, "shape")
    assertEquals(s.kind, SelectKind.OneHot)
    val labels = s.cases.map(_.values)
    assert(labels.forall(_.length == 1), labels.toString)
    // Not constants — which is the whole reason R22 could not carry them.
    assert(labels.flatten.forall {
      case Expr.Lit(_, _, _) => false
      case _                 => true
    })
  }

  test("`on` may be called by a producer, exactly as `is` may") {
    val cubes = List((0xF0, 0x70), (0xFC, 0x4C), (0xFF, 0x50))
    class Generated extends Fix:
      val opcode = In(UInt(8))
      val shape = Out(UInt(4))
      shape := 0.U(4)
      parallelCase {
        for (mask, value) <- cubes do
          on((opcode & mask.U(8)) === value.U(8)) { shape := 1.U(4) }
        default { shape := 0.U(4) }
      }
    assertEquals(onlySwitch(new Generated, "shape").cases.length, cubes.length)
  }

  test("one `on` may carry several conditions, sharing one body") {
    class Aliased extends Fix:
      val a = In(UInt(4))
      val out = Out(UInt(8))
      out := 0.U(8)
      priorityCase {
        on(a === 1.U(4), a === 2.U(4)) { out := 7.U(8) }
        default { out := 0.U(8) }
      }
    assertEquals(onlySwitch(new Aliased, "out").cases.head.values.length, 2)
  }

  test("R12 projects it per driver block, like any other switch") {
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
    val m = new Two
    val design = m.elaborateDesign()
    // Both projections keep every alternative — R11 reads them from here.
    for name <- List("x", "y") do
      val s = onlySwitchOf(design, name)
      assertEquals(s.cases.length, 2, name)
      assertEquals(s.kind, SelectKind.Priority, name)
  }
