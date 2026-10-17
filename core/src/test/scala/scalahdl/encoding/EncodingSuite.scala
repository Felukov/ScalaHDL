package scalahdl.encoding

import scalahdl.dsl.{*, given}
import scalahdl.elab.Module
import scalahdl.clock.ResetKind
import scalahdl.core.*
import scalahdl.core.ResetPolarity.ActiveHigh
import scalahdl.diag.{Code, ValidationFailed}
import scalahdl.emit.Emission

/** R24 — Named encodings.
  *
  * The claim under test is narrow and worth stating: an encoding removes a
  * repeated width from the source and changes nothing else. Members carry their
  * own width into every operand position, the names reach the diagnostics, and
  * the emitted Verilog is byte-for-byte what the same design written with
  * explicit literals emits — which is the check that this stayed a surface
  * construct rather than becoming a type. */
class EncodingSuite extends munit.FunSuite:
  import EncodingFixtures.*

  // ---- Declaration ----

  test("codes count from zero in declaration order, across multi-`val` lines"):
    assertEquals(AluOp.values.map(_.code), (0 until 10).toList)
    assertEquals(AluOp.Add.code, 0)
    assertEquals(AluOp.Or.code, 8)

  test("R1 capture names every member, including on a multi-`val` line"):
    assertEquals(AluOp.values.map(_.name),
      List("Add", "Sub", "Sll", "Slt", "Sltu", "Xor", "Srl", "Sra", "Or", "And"))
    assertEquals(AluOp.Or.toString, "AluOp.Or")
    assertEquals(AluOp.name, "AluOp")

  test("`Value(n)` pins a code and counting resumes at n + 1"):
    assertEquals(Sparse.values.map(m => m.name -> m.code),
      List("Zero" -> 0, "Four" -> 4, "Five" -> 5, "Twelve" -> 12, "Thirteen" -> 13))

  test("a pinned width is used verbatim; an inferred one is the minimum that fits"):
    assertEquals(AluOp.width, 4, "pinned by `Encoding(4)` even though 10 members need 4")
    assertEquals(Sparse.width, 4, "codes reach 13, which needs 4 bits")
    assertEquals(Compact.width, 2, "three members, codes 0..2")
    assertEquals(Single.width, 1, "one member at code 0 — never less than one bit")

  test("a member is the literal it stands for, at the encoding's width"):
    assertEquals(AluOp.Or.expr, Expr.uint(BigInt(8), 4))
    assertEquals(Compact.Third.expr, Expr.uint(BigInt(2), 2))

  // ---- Declaring signals ----

  test("In/Out/Wire/Reg from an encoding declare a UInt of its width"):
    class Decl extends Fix:
      val op = In(AluOp())
      val echoed = Out(AluOp())
      val staged = Wire(AluOp())
      val latched = Reg(AluOp(), init = AluOp.Add)
      staged := op
      echoed := staged
      latched := staged
    val top = (new Decl).elaborateDesign().top
    def typeOf(name: String) = top.signals.find(_.name == name).get.typeOf
    assertEquals(typeOf("op"), Type.UInt(4))
    assertEquals(typeOf("echoed"), Type.UInt(4))
    assertEquals(typeOf("staged"), Type.UInt(4))
    assertEquals(typeOf("latched"), Type.UInt(4))
    assertEquals(top.signals.find(_.name == "latched").get.init,
      Some(Expr.uint(BigInt(0), 4)),
      "a register's reset value is a member like any other constant")

  test("`In(AluOp)` and `In(AluOp())` are the same declaration"):
    class Parens extends Fix:
      val op = In(AluOp())
      val y = Out(UInt(8))
      y := Mux(op === AluOp.Add, 1.U(8), 0.U(8))
    class Bare extends Fix:
      val op = In(AluOp)
      val y = Out(UInt(8))
      y := Mux(op === AluOp.Add, 1.U(8), 0.U(8))
    assertEquals(portTypes(new Parens), portTypes(new Bare))

  // ---- Members as constants ----

  test("a member needs no width in `is`, `:=`, `Mux`, or `===`"):
    // Every one of these positions rejects a bare `1.U`: a case label has no
    // sibling to infer from, and R6.1 stops at an assignment boundary on
    // purpose. A member carries its own width, so there is nothing to infer.
    class Uses extends Fix:
      val op = In(AluOp())
      val alternate = In(Bool())
      val decoded = Wire(AluOp())
      val isAdd = Out(Bool())
      val y = Out(UInt(8))
      decoded := AluOp.Add
      switch(op) {
        is(AluOp.Sub) { decoded := Mux(alternate, AluOp.Sub, AluOp.Add) }
        is(AluOp.Or, AluOp.And) { decoded := AluOp.Or }
        default { decoded := AluOp.Add }
      }
      isAdd := decoded === AluOp.Add
      y := 0.U(8)
    val design = (new Uses).elaborateDesign()
    val switchStmt = design.top.drivers.flatMap(_.stmts).collectFirst {
      case s: Stmt.Switch => s
    }.get
    assertEquals(switchStmt.cases.map(_.values.map {
      case Expr.Lit(v, w, _) => (v.toInt, w)
      case other => fail(s"expected a literal, got $other")
    }), List(List((1, Some(4))), List((8, Some(4)), (9, Some(4)))))

  // ---- Diagnostics ----

  test("a switch alternative is reported by member name, not only bit pattern"):
    class Gap extends Fix:
      val op = In(AluOp())
      val y = Out(UInt(8))
      val z = Out(UInt(8))
      z := 0.U(8)
      switch(op) {
        is(AluOp.Add) { y := 1.U(8) }
        is(AluOp.Or) { z := 1.U(8) }
        default { y := 0.U(8) }
      }
    val failure = intercept[ValidationFailed]((new Gap).elaborateDesign())
    val diagnostic = failure.diagnostics.find(_.code == Code.COMBINATIONAL_INCOMPLETE).get
    assert(diagnostic.summary.contains("is(8 (AluOp.Or))"),
      s"the uncovered alternative names its member: ${diagnostic.summary}")

  test("a plain UInt selector still reports the bare bit pattern"):
    class Gap extends Fix:
      val op = In(UInt(4))
      val y = Out(UInt(8))
      val z = Out(UInt(8))
      z := 0.U(8)
      switch(op) {
        is(0.U(4)) { y := 1.U(8) }
        is(8.U(4)) { z := 1.U(8) }
        default { y := 0.U(8) }
      }
    val failure = intercept[ValidationFailed]((new Gap).elaborateDesign())
    val diagnostic = failure.diagnostics.find(_.code == Code.COMBINATIONAL_INCOMPLETE).get
    assert(diagnostic.summary.contains("is(8)"), diagnostic.summary)

  test("a pinned code that does not fit the pinned width is an error"):
    val failure = intercept[ValidationFailed](new OverRange)
    val diagnostic = failure.diagnostics.head
    assertEquals(diagnostic.code, Code.ENCODING_CODE_OUT_OF_RANGE)
    assert(diagnostic.summary.contains("needs 5 bits"), diagnostic.summary)
    assert(diagnostic.summary.contains("is 4 wide"), diagnostic.summary)

  test("two members sharing a code is an error naming both"):
    val failure = intercept[ValidationFailed](new Colliding)
    val diagnostic = failure.diagnostics.head
    assertEquals(diagnostic.code, Code.ENCODING_DUPLICATE_CODE)
    assert(diagnostic.related.exists(_.note.contains("`First` already has code `0`")),
      diagnostic.related.toString)

  test("a member declared after the width was read is an error"):
    val late = new Late
    assertEquals(late.width, 1)
    val failure = intercept[ValidationFailed](late.addLater())
    assertEquals(failure.diagnostics.head.code, Code.ENCODING_SEALED)

  // ---- Emission ----

  test("an encoded design emits the bytes the literal design emits"):
    // The whole of R24's claim to be a surface construct, in one assertion.
    assertEquals(emitBody(new Encoded), emitBody(new Literal))
    assert(emitBody(new Encoded).contains("4'd8: y = 8'd99;"), emitBody(new Encoded))
    assert(!emitBody(new Encoded).contains("AluOp"),
      "an encoding is a source-level fact: no localparam, no name in the Verilog")

object EncodingFixtures:

  object AluOp extends Encoding(4):
    val Add, Sub, Sll, Slt, Sltu, Xor, Srl, Sra, Or, And = Value

  /** Codes fixed by something outside the declaration — the shape a `funct3`
    * or a response encoding has. */
  object Sparse extends Encoding(4):
    val Zero = Value
    val Four = Value(4)
    val Five = Value
    val Twelve = Value(12)
    val Thirteen = Value

  object Compact extends Encoding():
    val First, Second, Third = Value

  object Single extends Encoding():
    val Only = Value

  class OverRange extends Encoding(4):
    val Fits = Value
    val Overflows = Value(16)

  class Colliding extends Encoding(4):
    val First = Value
    val Second = Value(0)

  class Late extends Encoding():
    val Early = Value
    def addLater(): EncodingValue = Value

  abstract class Fix extends Module:
    val clk = In(Clock())
    val rst = In(Reset(ActiveHigh))
    given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)

  /** The same decoder twice: once through an encoding, once with the literals
    * the encoding stands for. */
  class Encoded extends Fix:
    val op = In(AluOp())
    val y = Out(UInt(8))
    y := 0.U(8)
    switch(op) {
      is(AluOp.Add) { y := 11.U(8) }
      is(AluOp.Or) { y := 99.U(8) }
      default { y := 1.U(8) }
    }

  class Literal extends Fix:
    val op = In(UInt(4))
    val y = Out(UInt(8))
    y := 0.U(8)
    switch(op) {
      is(0.U(4)) { y := 11.U(8) }
      is(8.U(4)) { y := 99.U(8) }
      default { y := 1.U(8) }
    }

  def portTypes(m: Module): List[(String, Type)] =
    m.elaborateDesign().top.ports.map(p => p.name -> p.typeOf)

  /** The emitted module with its name erased, so two spellings of one design
    * differ only where their source did. */
  def emitBody(m: Module): String =
    val emitted = new Emission("0.0.0-TEST").emit(m.elaborateDesign()).files.head._2
    emitted.replace(m.moduleName, "M")
