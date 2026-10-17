package scalahdl.validate

import scalahdl.clock.ResetKind
import scalahdl.core.ResetPolarity.ActiveHigh
import scalahdl.diag.{Code, Diagnostic, ValidationFailed}
import scalahdl.dsl.{*, given}
import scalahdl.elab.Module
import scalahdl.emit.Emission

/** R6.7 — a part-select is an assignment target.
  *
  * The requirement exists because of an asymmetry that was a bug: `sig(i)`
  * returned a handle and could be written, `sig(hi, lo)` returned a bare
  * expression and could not — though Verilog spells both as one part-select,
  * and a design that needs one usually needs the other. A 6522's timers are
  * where it was found: `timer_a_count <= timer_a_latch;` immediately followed
  * by `if(write) timer_a_count[7:0] <= data_in;` is one 16-bit value written as
  * two statements, and without this the port has to name the value the first
  * statement would have produced.
  *
  * So the obligations are: the window is writable and emits the part-select a
  * reader of the source would have written; reading through the same surface
  * is unchanged, to the character; and the three ways a window cannot be a
  * target are errors rather than surprises — a combinational signal, a bound
  * outside the signal, and an element of a `Vec`, which Verilog-2001 has no
  * part-select for.
  */
class PartialWriteSuite extends munit.FunSuite:

  private def emit(m: => Module): String =
    new Emission("0.0.0-TEST").emit(m.elaborateDesign()).files.head._2

  private def expectFailure(body: => Module): List[Diagnostic] =
    intercept[ValidationFailed] { body.elaborateDesign() }.diagnostics

  private def codes(ds: List[Diagnostic]): List[Code] = ds.map(_.code)

  // ---- the shape this rule was written for
  //
  // A 16-bit counter reloaded from a pair of 8-bit latches, with the low half
  // taken from the bus instead when a write lands on the same enable. That is
  // `via6522.v`'s Timer 1, and the two statements are the point: the second
  // refines the first, and both target one register.

  private class Timer extends Module:
    val clk     = In(Clock())
    val en      = In(Bool())
    val reload  = In(Bool())
    val write   = In(Bool())
    val data    = In(UInt(8))
    val latch   = In(UInt(16))
    val count   = Out(UInt(16))

    given clocking: ClockDomain = ClockDomain(clk, ResetKind.None)

    val counter = Reg(UInt(16), powerUp = 0x5550)
    when(en) {
      when(reload) {
        counter := latch
        when(write) { counter(7, 0) := data }
      }.otherwise {
        counter := counter - 1
      }
    }
    count := counter

  test("a part-select target emits a Verilog part-select"):
    val verilog = emit(new Timer)
    assert(verilog.contains("counter <= latch;"), verilog)
    assert(verilog.contains("counter[7:0] <= data;"), verilog)

  test("the two statements keep their order, so the second refines the first (R12)"):
    val verilog = emit(new Timer)
    assert(verilog.indexOf("counter <= latch;") < verilog.indexOf("counter[7:0] <= data;"),
           verilog)

  // ---- one window, one spelling
  //
  // R6.2 already decides that a one-bit *read* is `x[7]` rather than the
  // degenerate `x[7:7]`. A target has to agree, or the same bit would have two
  // spellings depending on which surface named it.

  private class OneBit extends Module:
    val clk  = In(Clock())
    val d    = In(Bool())
    val q    = Out(UInt(8))

    given clocking: ClockDomain = ClockDomain(clk, ResetKind.None)

    val r = Reg(UInt(8), powerUp = 0)
    r(7, 7) := d      // a window of one
    r(3) := d         // and a bit-select, which was always writable
    q := r

  test("a one-bit window is a bit-select, not a degenerate part-select"):
    val verilog = emit(new OneBit)
    assert(verilog.contains("r[7] <= d;"), verilog)
    assert(!verilog.contains("r[7:7]"), verilog)
    assert(verilog.contains("r[3] <= d;"), verilog)

  // ---- reading is unchanged
  //
  // `sig(hi, lo)` changed return type, which is the one way this could have
  // moved something it was not meant to. Every read below is the form that
  // existed before R6.7, and the assertions are the text it emitted then.

  private class PlainReads extends Module:
    val a    = In(UInt(16))
    val wide = Out(UInt(8))
    val sum  = Out(UInt(8))
    val cat  = Out(UInt(12))
    val res  = Out(UInt(16))

    given clocking: ClockDomain = scalahdl.clock.ClockDomain.noClock

    wide := a(15, 8)
    sum  := a(7, 0) + a(15, 8)
    cat  := Cat(a(11, 8), a(7, 0))
    res  := a(7, 0).resize(16)

  test("reads through the window surface emit exactly what they emitted before"):
    val verilog = emit(new PlainReads)
    assert(verilog.contains("wide = a[15:8];"), verilog)
    assert(verilog.contains("sum = (a[7:0] + a[15:8]);"), verilog)
    assert(verilog.contains("cat = {a[11:8], a[7:0]};"), verilog)
    // R21.4 through the same handle: a widening resize of a window
    assert(verilog.contains("res = "), verilog)

  test("selecting inside a window is R6.2's error, as it was before"):
    val bad = intercept[Exception] {
      new Module:
        val a = In(UInt(16))
        val q = Out(Bool())
        given clocking: ClockDomain = scalahdl.clock.ClockDomain.noClock
        q := a(15, 8)(0)
      .elaborateDesign()
    }
    assert(bad.getMessage.contains("requires a named signal"), bad.getMessage)

  test("a reversed window is refused where it is written"):
    val bad = intercept[IllegalArgumentException] {
      new Module:
        val clk = In(Clock())
        val d = In(UInt(8))
        given clocking: ClockDomain = ClockDomain(clk, ResetKind.None)
        val r = Reg(UInt(16), powerUp = 0)
        r(0, 7) := d
      .elaborateDesign()
    }
    assert(bad.getMessage.contains("reversed"), bad.getMessage)

  // ---- the three ways a window cannot be a target

  test("a window on a combinational signal is an error naming the latch"):
    val ds = expectFailure {
      new Module:
        val d = In(UInt(8))
        val q = Out(UInt(16))
        given clocking: ClockDomain = scalahdl.clock.ClockDomain.noClock
        val w = Wire(UInt(16))
        w(7, 0) := d
        q := w
    }
    assertEquals(codes(ds).distinct.contains(Code.PARTIAL_WRITE_COMBINATIONAL), true,
                 ds.map(_.summary).mkString("\n"))
    assert(ds.exists(_.summary.contains("latch")), ds.map(_.summary).mkString("\n"))
    assert(ds.exists(_.fix.exists(_.contains("Cat"))), ds.map(_.fix).mkString("\n"))

  test("a window outside the signal is an error naming the range"):
    val ds = expectFailure {
      new Module:
        val clk = In(Clock())
        val d = In(UInt(8))
        given clocking: ClockDomain = ClockDomain(clk, ResetKind.None)
        val r = Reg(UInt(8), powerUp = 0)
        r(15, 8) := d
    }
    assertEquals(codes(ds).contains(Code.PARTIAL_WRITE_OUT_OF_BOUNDS), true,
                 ds.map(_.summary).mkString("\n"))
    assert(ds.exists(_.summary.contains("7..0")), ds.map(_.summary).mkString("\n"))

  test("a window on a Vec element is an error, because Verilog-2001 has none"):
    val ds = expectFailure {
      new Module:
        val clk = In(Clock())
        val d = In(UInt(8))
        given clocking: ClockDomain = ClockDomain(clk, ResetKind.None)
        val v = Reg(Vec(4, UInt(16)))
        v(2)(7, 0) := d
    }
    assertEquals(codes(ds).contains(Code.PARTIAL_WRITE_VEC_ELEMENT), true,
                 ds.map(_.summary).mkString("\n"))

  // ---- the window is a width, and the width is checked

  test("a value of the wrong width is R6's error at the window, not at the signal"):
    val ds = expectFailure {
      new Module:
        val clk = In(Clock())
        val d = In(UInt(16))
        given clocking: ClockDomain = ClockDomain(clk, ResetKind.None)
        val r = Reg(UInt(16), powerUp = 0)
        r(7, 0) := d
    }
    assertEquals(codes(ds).contains(Code.WIDTH_MISMATCH), true,
                 ds.map(_.summary).mkString("\n"))

  test("a whole-right-hand-side literal takes the window's width (R6.3)"):
    val verilog = emit {
      new Module:
        val clk = In(Clock())
        val q = Out(UInt(16))
        given clocking: ClockDomain = ClockDomain(clk, ResetKind.None)
        val r = Reg(UInt(16), powerUp = 0)
        r(7, 0) := 0xff
        q := r
    }
    assert(verilog.contains("r[7:0] <= 8'd255;"), verilog)

  // ---- a bundle field is a plain name, so a window on one is ordinary

  private class Framed extends Bundle:
    val head = UInt(8)
    val body = UInt(16)

  test("a window on a bundle field of a register is a plain part-select"):
    val verilog = emit {
      new Module:
        val clk = In(Clock())
        val d = In(UInt(8))
        given clocking: ClockDomain = ClockDomain(clk, ResetKind.None)
        val f = Reg(new Framed)
        f.body(7, 0) := d
        f.head := d
    }
    assert(verilog.contains("f_body[7:0] <= d;"), verilog)

  // ---- a domain with a reset is untouched

  test("a window in a reset domain lands in the else arm, like any other write"):
    val verilog = emit {
      new Module:
        val clk = In(Clock())
        val rst = In(Reset(ActiveHigh))
        val d = In(UInt(8))
        val q = Out(UInt(16))
        given clocking: ClockDomain =
          ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)
        val r = Reg(UInt(16), init = 0)
        r(7, 0) := d
        q := r
    }
    assert(verilog.contains("if (rst) begin\n      r <= 16'd0;\n    end"), verilog)
    assert(verilog.contains("end else begin\n      r[7:0] <= d;\n    end"), verilog)
