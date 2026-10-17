package scalahdl.diag

import java.nio.file.Paths
import scalahdl.dsl.{*, given}
import scalahdl.elab.Module
import scalahdl.clock.ResetKind
import scalahdl.core.*
import scalahdl.core.ResetPolarity.ActiveHigh

/** R19 — "Two locations matter and they differ: where a signal was *declared*
  * and where it was *used wrongly*", and R19's closing sentence names where the
  * second comes from: "Locations are captured by the R1 macro at construction,
  * on declarations, on `:=`, on `when`, and on memory operations."
  *
  * `DiagGoldenSuite` proves the renderer draws that block; this suite proves
  * elaboration *fills* it — that the primary location of a width or kind error
  * is the statement the user wrote, not one of the declarations the `note:`
  * lines already carry. Before this, `:=`/`when`/`write` recorded `Loc.NoLoc`
  * and the phase fell back to a declaration, so `a === b` with mismatched
  * widths pointed at `val a = In(UInt(8))` — a line that is correct, is shared
  * by every misuse of `a`, and says nothing about which one is being reported.
  *
  * Expected line numbers are *found*, not written down: each interesting
  * statement carries a `// @<tag>` marker and the test reads this file back to
  * locate it. Editing the suite moves the markers with the code. */
class UseSiteLocSuite extends munit.FunSuite:

  private val sourceDir = Paths.get("core/src/test/scala/scalahdl/diag")
  private given src: ExcerptSource = ExcerptSource.fromDir(sourceDir)

  /** The 1-based line carrying `// @tag` in this very file. */
  private def lineOf(tag: String): Int =
    val lines = java.nio.file.Files.readAllLines(sourceDir.resolve("UseSiteLocSuite.scala"))
    val idx = (0 until lines.size).find(i => lines.get(i).nn.contains(s"// @$tag"))
    idx.map(_ + 1).getOrElse(fail(s"no `// @$tag` marker in UseSiteLocSuite.scala"))

  private def diagnose(body: => Module): List[Diagnostic] =
    intercept[ValidationFailed] { body.elaborateDesign() }.diagnostics

  abstract class Fix extends Module:
    val clk = In(Clock())
    val rst = In(Reset(ActiveHigh))
    given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)

  // ---- the reported case: comparing two signals of different width -------

  test("comparing two different widths is reported at the comparison, not at a declaration"):
    class Bad extends Fix:
      val a = In(UInt(8))
      val b = In(UInt(12))
      val y = Out(Bool())
      y := (a === b)                                                    // @cmp
    val d = diagnose(new Bad).head
    assertEquals(d.code, Code.WIDTH_MISMATCH)
    assertEquals(d.primaryLoc.file, "UseSiteLocSuite.scala")
    assertEquals(d.primaryLoc.line, lineOf("cmp"),
      s"the caret belongs on the `===`, not on a declaration: ${d.render}")

  test("both declarations survive as related notes under the use site"):
    class Bad extends Fix:
      val a = In(UInt(8))                                               // @declA
      val b = In(UInt(12))                                              // @declB
      val y = Out(Bool())
      y := (a === b)
    val d = diagnose(new Bad).head
    val notes = d.related.map(r => (r.loc.line, r.note))
    assert(notes.exists((l, n) => l == lineOf("declA") && n.contains("`a`") && n.contains("8")),
      s"`a`'s declaration should still be a note: $notes")
    assert(notes.exists((l, n) => l == lineOf("declB") && n.contains("`b`") && n.contains("12")),
      s"`b`'s declaration should still be a note: $notes")
    assert(!d.related.exists(_.loc == d.primaryLoc),
      s"the primary location should not repeat a note's location: ${d.render}")

  test("the rendered block puts the caret on the offending source line (R19 end-to-end)"):
    class Bad extends Fix:
      val a = In(UInt(8))
      val b = In(UInt(12))
      val y = Out(Bool())
      y := (a === b)                                                    // @rendered
    val rendered = diagnose(new Bad).head.render
    val line = lineOf("rendered")
    assert(rendered.contains(s"  --> UseSiteLocSuite.scala:$line:"), s"primary loc:\n$rendered")
    assert(rendered.contains("y := (a === b)"), s"source excerpt:\n$rendered")
    assert(rendered.contains("^ operands must have equal width"), s"caret note:\n$rendered")

  // ---- the same error from every statement that carries a location ------

  test("a condition's width error is reported at its `when`"):
    class Bad extends Fix:
      val a = In(UInt(8))
      val b = In(UInt(12))
      val y = Out(UInt(8))
      when(a === b) {                                                   // @when
        y := a
      }
    val d = diagnose(new Bad).head
    assertEquals(d.primaryLoc.line, lineOf("when"), s"${d.render}")

  test("a chained `elsewhen` reports at the `when` heading its chain (known limit)"):
    // Not the arm's own line, and the reason is a Scala 3 one: a chain is one
    // expression, so the position of the call being inlined is the receiver's.
    // See `When.scala` — the condition's own tree is not reachable from an
    // `inline` parameter. Asserted rather than left unstated so the day it can
    // be improved, this test is what says so.
    class Bad extends Fix:
      val a = In(UInt(8))
      val b = In(UInt(12))
      val y = Out(UInt(8))
      when(a === 0) {                                                   // @chainHead
        y := a
      }.elsewhen(a === b) {
        y := a
      }
    val d = diagnose(new Bad).head
    assertEquals(d.primaryLoc.line, lineOf("chainHead"), s"${d.render}")

  test("an assignment nested in a `when` arm reports at the assignment, not the `when`"):
    class Bad extends Fix:
      val a = In(UInt(8))
      val c = In(Bool())
      val y = Out(UInt(12))
      when(c) {
        y := a                                                          // @nested
      }
    val d = diagnose(new Bad).head
    assertEquals(d.primaryLoc.line, lineOf("nested"), s"${d.render}")

  test("a `switch` selector's width error is reported at the `switch`"):
    class Bad extends Fix:
      val a = In(UInt(4))
      val b = In(UInt(8))
      val y = Out(UInt(8))
      y := 0
      switch(a & b) {                                                   // @switch
        is(1) { y := 1 }
        default { y := 0 }
      }
    val d = diagnose(new Bad).head
    assertEquals(d.code, Code.WIDTH_MISMATCH)
    assertEquals(d.primaryLoc.line, lineOf("switch"), s"${d.render}")

  test("a memory write records the `write` call site (R19: 'and on memory operations')"):
    // No phase width-checks a memory port's address or data yet — writes live
    // on the `Memory` IR node rather than in a driver block, so `WidthPhase`
    // never reaches them. The location is what this asserts, because it is what
    // changed: `write` used to record `NoLoc`, so `CdcPhase`'s crossing report
    // on a write had no line to name.
    class Ram extends Fix:
      val addr = In(UInt(4))
      val data = In(UInt(8))
      val y    = Out(UInt(8))
      val ram  = Registers(16, UInt(8))
      ram.write(addr, data)                                             // @memwrite
      val rd = ram.readAsync(addr, ReadFirst)
      y := rd
    val port = (new Ram).elaborateDesign().top.memories.head.writes.head
    assertEquals(port.loc.file, "UseSiteLocSuite.scala")
    assertEquals(port.loc.line, lineOf("memwrite"))

  test("an inline helper's misuse is reported at the caller, not inside the helper"):
    // `Loc.here` is the position of the *call being inlined*, which is the
    // outermost one — so a condition or right-hand side built by somebody's
    // `inline def` still names the line that called it. This is the property
    // that makes the location worth having, and it is easy to lose: reaching
    // through the inlining for "the real tree" lands in the helper's body.
    class Bad extends Fix:
      val a = In(UInt(8))
      val b = In(UInt(12))
      val y = Out(Bool())
      val z = Out(Bool())
      when(UseSiteLocSuite.mismatched(a, b)) {                          // @helperCond
        z := True
      }.otherwise {
        z := False
      }
      y := UseSiteLocSuite.mismatched(a, b)                             // @helperRhs
    val lines = diagnose(new Bad).map(_.primaryLoc.line).distinct.sorted
    assertEquals(lines, List(lineOf("helperCond"), lineOf("helperRhs")))

  // ---- the reason the use site is the one worth having ------------------

  test("two misuses of the same signals report at two different lines"):
    // The declarations are shared, so a declaration-anchored diagnostic named
    // the same line twice and left the reader to guess which `===` it meant.
    // The use sites are what differ, and they are what says which one to fix.
    //
    // A Scala `def` that wraps the misuse is still one location for all its
    // callers — `:=` inlines into the helper's body, not into each call — and
    // that is R19.1's job rather than this one: the stack trace and elaboration
    // path are what answer "which call to this generator produced it".
    class Bad extends Fix:
      val narrow = In(UInt(8))
      val wide   = In(UInt(12))
      val p = Out(Bool())
      val q = Out(Bool())
      p := (narrow === wide)                                            // @siteOne
      q := (narrow === wide)                                            // @siteTwo
    val lines = diagnose(new Bad).map(_.primaryLoc.line).distinct.sorted
    assertEquals(lines, List(lineOf("siteOne"), lineOf("siteTwo")))

  // ---- R6.1/R21.7 — the builder's literal errors carry one too ----------

  test("an over-wide literal is reported at the statement that wrote it"):
    // The literal diagnostics are raised in `Builder.freeze`, after the DSL
    // call that wrote them has returned, so they had no statement in hand and
    // rendered as a bare header — no `-->` line, no excerpt, no caret. That is
    // the one shape R19 exists to rule out, and it survived the use-site work
    // because that work went into `WidthPhase` and these are raised before any
    // phase runs. `Builder.LitCtx` carries the enclosing statement's `Loc`
    // down the resolver to reach them.
    class Bad extends Fix:
      val y = Out(Bool())
      y := 100                                                          // @litAssign
    val d = diagnose(new Bad).head
    assertEquals(d.code, Code.WIDTH_MISMATCH)
    assertEquals(d.primaryLoc.file, "UseSiteLocSuite.scala")
    assertEquals(d.primaryLoc.line, lineOf("litAssign"), s"${d.render}")

  test("a literal names the operand that supplied its width, not the target"):
    // `flag` is what gives the literal its one bit: R6.3's whole-RHS rule is a
    // leaf rule, so a `BinOp` right-hand side is resolved by sibling inference
    // and `y` never offers anything. Naming `y` would point at a signal with no
    // part in the error — and would read as correct here only because `y`
    // happens to be one bit too.
    class Bad extends Fix:
      val flag = In(Bool())                                             // @litSibling
      val y = Out(Bool())
      y := (flag === 100)                                               // @litCmp
    val d = diagnose(new Bad).head
    assertEquals(d.primaryLoc.line, lineOf("litCmp"), s"${d.render}")
    assert(d.primaryNote.exists(_.contains("`flag`")),
      s"the caret note should name the operand that supplied the width: ${d.render}")
    assertEquals(d.related.map(_.loc.line), List(lineOf("litSibling")), s"${d.render}")

  test("a literal in a memory address is reported at the `write`"):
    // Ports carry their own locations, so `MemoryBuilder.freeze` hands each
    // expression the port's line rather than the `Memory` declaration shared by
    // every port — R19's "and on memory operations", for literals as well as
    // for the crossing reports that already had it.
    //
    // The literal is inside the address expression rather than standing alone
    // as the data: a bare `ram.write(addr, 300)` never reaches the resolver at
    // all, because `Memory`'s DSL rejects an unwidthed literal argument at the
    // call with its own `MEM_*` error. This is the shape that does get here —
    // `20` takes `addr`'s four bits and does not fit in them.
    class Bad extends Fix:
      val addr = In(UInt(4))
      val data = In(UInt(8))
      val y    = Out(UInt(8))
      val ram  = Registers(16, UInt(8))
      ram.write(addr + 20, data)                                        // @litMemWrite
      val rd = ram.readAsync(addr, ReadFirst)
      y := rd
    val d = diagnose(new Bad).head
    assertEquals(d.primaryLoc.line, lineOf("litMemWrite"), s"${d.render}")

  test("a rendered literal error draws the caret block, not a bare header"):
    class Bad extends Fix:
      val y = Out(UInt(4))
      y := 100                                                          // @litRendered
    val rendered = diagnose(new Bad).head.render
    val line = lineOf("litRendered")
    assert(rendered.contains(s"  --> UseSiteLocSuite.scala:$line:"), s"primary loc:\n$rendered")
    assert(rendered.contains("y := 100"), s"source excerpt:\n$rendered")
    assert(rendered.contains("^ `y` supplies 4 bits here"), s"caret note:\n$rendered")

object UseSiteLocSuite:
  /** A generator of the kind a user writes: `inline`, so it composes at the
    * call site, and wrong in a way only its arguments can make it. */
  inline def mismatched(x: DslSignal, y: DslSignal): Expr = x === y
