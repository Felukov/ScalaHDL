package scalahdl.switchcase

import scalahdl.dsl.{*, given}
import scalahdl.elab.Module
import scalahdl.emit.Emission

/** R22/R12/R20.1 — the emitted Verilog is a native `case`, and it is the IR's
  * shape verbatim: one item per alternative in elaboration order, aliases as
  * comma-separated labels on one item, `default` only when the source had one.
  * No sorting, no merging of equal bodies, no `if` chain, no ternary. */
class SwitchEmissionSuite extends munit.FunSuite:
  import SwitchFixtures.*

  private def emit(m: => Module): String =
    new Emission("0.0.0-TEST").emit(m.elaborateDesign()).files.head._2

  private class Decoder extends Fix:
    val opcode = In(UInt(3))
    val result = Out(UInt(8))
    val illegal = Out(Bool())
    result := 0.U(8)
    illegal := 0.U(1)
    switch(opcode) {
      is(0.U(3)) { result := 11.U(8) }
      is(5.U(3), 6.U(3)) { result := 99.U(8) }
      default { illegal := 1.U(1) }
    }

  /** The FSM shape R22.1 is about: one `switch(state)` shared between the state
    * register and the registers it latches, so every projection but `state`'s
    * own has more arms that say nothing than arms that do. */
  private class Fsm extends Fix:
    val data = In(UInt(8))
    val state = Reg(UInt(2), init = 0.U(2))
    val latched = Reg(UInt(8), init = 0.U(8))
    val seen = Reg(Bool(), init = 0.U(1))
    switch(state) {
      is(0.U(2)) { state := 1.U(2) }
      is(1.U(2)) {
        state := 2.U(2)
        latched := data
      }
      is(2.U(2)) {
        state := 3.U(2)
        seen := 1.U(1)
      }
      is(3.U(2)) { state := 0.U(2) }
    }

  /** The `always` block driving `name`, as trimmed non-empty lines. `end`
    * closes a `begin`; `endcase` and `endmodule` are different tokens. */
  private def alwaysBlock(verilog: String, name: String): List[String] =
    val begins = """\bbegin\b""".r
    val ends = """\bend\b""".r
    val blocks = List.newBuilder[List[String]]
    var current = List.newBuilder[String]
    var depth = 0
    verilog.linesIterator.map(_.trim).filter(_.nonEmpty).foreach { line =>
      if depth == 0 && line.startsWith("always @") then
        current = List.newBuilder[String]
      if depth > 0 || line.startsWith("always @") then
        current += line
        depth += begins.findAllIn(line).size - ends.findAllIn(line).size
        if depth == 0 then blocks += current.result()
    }
    val assigns = s""".*\\b$name\\s*<?=.*""".r
    blocks.result()
      .find(_.exists(assigns.matches))
      .getOrElse(fail(s"no always block assigns $name:\n$verilog"))

  test("a switch emits one native `case` statement"):
    val verilog = emit(new Decoder)
    assert(verilog.contains("case (opcode)"), verilog)
    assert(verilog.contains("endcase"), verilog)
    assertEquals(verilog.sliding("case (opcode)".length).count(_ == "case (opcode)"), 2,
      "one `case` per driver block the switch was projected into")

  test("aliases share one item, as comma-separated labels in order"):
    val verilog = emit(new Decoder)
    assert(verilog.contains("3'd5, 3'd6: result = 8'd99;"), verilog)

  test("`default` appears only where the source had one"):
    val verilog = emit(new Decoder)
    assert(verilog.contains("default: illegal = 1'b1;"), verilog)
    class NoDefault extends Fix:
      val sel = In(UInt(2))
      val y = Out(UInt(8))
      y := 0.U(8)
      switch(sel) { is(0.U(2)) { y := 1.U(8) } }
    assert(!emit(new NoDefault).contains("default:"),
      "a switch without a `default` does not grow one")

  test("an empty arm is kept when the default assigns this signal"):
    // R22.1 — `illegal` is written only by the default, so in its projection
    // the empty arms are what keep `0`, `5` and `6` *out* of the default.
    // Dropping them would make the decoder report every opcode illegal.
    val verilog = emit(new Decoder)
    assert(verilog.contains("3'd0: ;"), verilog)
    assert(verilog.contains("3'd5, 3'd6: ;"), verilog)

  test("an alternative that writes nothing here is dropped for `default: ;`"):
    // R22.1 — the FSM shape: one switch shared between several registers, so
    // each projection has one arm that writes and three that do not.
    val verilog = emit(new Fsm)
    val block = alwaysBlock(verilog, "latched")
    assertEquals(block, List(
      "always @(posedge clk) begin",
      "if (rst) begin",
      "latched <= 8'd0;",
      "end else begin",
      "case (state)",
      "2'd1: latched <= data;",
      "default: ;",
      "endcase",
      "end",
      "end"))

  test("a projection that writes in every arm is unchanged, and grows no default"):
    // Nothing was pruned, so nothing is put back: `state`'s own projection is
    // the switch exactly as elaborated (R22).
    val verilog = emit(new Fsm)
    assertEquals(alwaysBlock(verilog, "state"), List(
      "always @(posedge clk) begin",
      "if (rst) begin",
      "state <= 2'd0;",
      "end else begin",
      "case (state)",
      "2'd0: state <= 2'd1;",
      "2'd1: state <= 2'd2;",
      "2'd2: state <= 2'd3;",
      "2'd3: state <= 2'd0;",
      "endcase",
      "end",
      "end"))

  test("the source's own `default` is never duplicated by the pruning one"):
    // `result`'s projection has an empty default (the source's default writes
    // `illegal`, not `result`) and no arm to prune — one `default:`, from the
    // source, exactly as before R22.1.
    val verilog = emit(new Decoder)
    assertEquals(verilog.sliding("default:".length).count(_ == "default:"), 2,
      s"one per driver block, never two in one case: $verilog")

  test("a nested switch is projected and pruned like any other"):
    class Nested extends Fix:
      val outer = In(UInt(2))
      val inner = In(UInt(2))
      val data = In(UInt(8))
      val held = Reg(UInt(8), init = 0.U(8))
      val other = Reg(UInt(8), init = 0.U(8))
      switch(outer) {
        is(0.U(2)) {
          switch(inner) {
            is(1.U(2)) { held := data }
            is(2.U(2)) { other := data }
          }
        }
      }
    val verilog = emit(new Nested)
    assertEquals(alwaysBlock(verilog, "held"), List(
      "always @(posedge clk) begin",
      "if (rst) begin",
      "held <= 8'd0;",
      "end else begin",
      "case (outer)",
      "2'd0: case (inner)",
      "2'd1: held <= data;",
      "default: ;",
      "endcase",
      "endcase",
      "end",
      "end"))

  test("statements inside an alternative keep their order and nesting"):
    class Nested extends Fix:
      val sel = In(UInt(1))
      val go = In(Bool())
      val y = Reg(UInt(8), init = 0.U(8))
      val out = Out(UInt(8))
      out := y
      switch(sel) {
        is(0.U(1)) {
          y := 1.U(8)
          when(go) { y := 2.U(8) }
        }
      }
    val verilog = emit(new Nested)
    val body = verilog.linesIterator.dropWhile(!_.contains("case (sel)")).take(7).toList
    assertEquals(body(1).trim, "1'b0: begin")   // a 1-bit label is `1'b`, per R12's literal rule
    assertEquals(body(2).trim, "y <= 8'd1;")
    assertEquals(body(3).trim, "if (go) begin")
    assertEquals(body(4).trim, "y <= 8'd2;")
    assertEquals(body(5).trim, "end")
    assertEquals(body(6).trim, "end")

  test("emission is byte-for-byte reproducible"):
    assertEquals(emit(new Decoder), emit(new Decoder))

  test("a switch never becomes an `if` chain"):
    val verilog = emit(new Decoder)
    assert(!verilog.contains("if (opcode"), s"R22: the selector is not re-tested: $verilog")
    assert(!verilog.contains("?"), s"R12: no ternary collapse: $verilog")
