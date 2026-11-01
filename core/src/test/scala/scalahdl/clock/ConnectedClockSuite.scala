package scalahdl.clock

import scalahdl.dsl.{*, given}
import scalahdl.clock.ResetKind
import scalahdl.elab.{Module, BlackBox}
import scalahdl.core.*
import scalahdl.core.ResetPolarity.{ActiveHigh, ActiveLow}
import scalahdl.emit.VerilogEmitter

/** R13.6 — An explicit clock connection is a domain statement.
  *
  * A port carries `noClock` (R13.1), so the parent-side net `<inst>_<port>` is
  * the only place a cross-module crossing is decided. R13.2 promised the
  * explicit override and delivered it for the wire but not for the fact, so
  * `u.clk := slowClk` connected the child correctly and left R05 blind to
  * everything it drove.
  *
  * Each test maps to a checkbox in
  * `roadmap/open/M30-domain-integrity/R13.6-connected-clock-is-the-domain.md`. */
class ConnectedClockSuite extends munit.FunSuite:

  class SlowUnit extends Module:
    val clk   = In(Clock())
    val rst_n = In(Reset(ActiveLow))
    given own: ClockDomain = ClockDomain(clk, rst_n, ResetKind.Async, ActiveLow)
    val q = Out(UInt(8))
    val cnt = Reg(UInt(8), init = 0)
    cnt := cnt + 1
    q := cnt

  abstract class TwoClocks extends Module:
    val clk       = In(Clock())
    val rst_n     = In(Reset(ActiveLow))
    val slowClk   = In(Clock())
    val slowRst_n = In(Reset(ActiveLow))
    given fast: ClockDomain = ClockDomain(clk, rst_n, ResetKind.Async, ActiveLow)
    lazy val slow: ClockDomain =
      ClockDomain(slowClk, slowRst_n, ResetKind.Async, ActiveLow)

  private def verdict(m: => Module): Either[String, scalahdl.core.Module] =
    scala.util.Try(m.elaborateDesign().top).toEither.left.map {
      case v: scalahdl.diag.ValidationFailed => v.diagnostics.map(_.summary).mkString(" | ")
      case e                                 => String.valueOf(e.getMessage)
    }

  // ---- the two spellings agree ------------------------------------------

  test("wiring the pins and naming the domain give the same CDC verdict"):
    class ViaPins extends TwoClocks:
      val out = Out(UInt(8))
      val u = inst(new SlowUnit())
      u.clk   := slowClk
      u.rst_n := slowRst_n
      val capture = Reg(UInt(8), init = 0)     // in `fast`
      capture := u.q                           // reads slowClk data — a crossing
      out := capture
    class ViaDomain extends TwoClocks:
      val out = Out(UInt(8))
      // The given-block form — `withClock` cannot serve here, because its body
      // is `ClockDomain ?=> Unit` and `u` would not survive the block (R4.1).
      val u = { given ClockDomain = slow; inst(new SlowUnit()) }
      val capture = Reg(UInt(8), init = 0)
      capture := u.q
      out := capture
    val pins   = verdict(new ViaPins)
    val domain = verdict(new ViaDomain)
    assert(domain.isLeft, s"baseline: naming the domain catches it: $domain")
    assert(pins.isLeft,
      s"wiring `u.clk := slowClk` must state the domain too, or R05 goes blind " +
      s"on everything the child drives (R13.6): $pins")
    assertEquals(pins, domain, "same design, same verdict, word for word")

  test("the connected clock names the domain in the message"):
    class M extends TwoClocks:
      val out = Out(UInt(8))
      val u = inst(new SlowUnit())
      u.clk   := slowClk
      u.rst_n := slowRst_n
      val capture = Reg(UInt(8), init = 0)
      capture := u.q
      out := capture
    val e = verdict(new M).left.getOrElse(fail("expected a diagnostic"))
    assert(e.contains("belongs to `slowClk`"), e)
    assert(e.contains("read in clock domain `clk`"), e)

  test("order does not matter — reset first, then clock"):
    class M extends TwoClocks:
      val out = Out(UInt(8))
      val u = inst(new SlowUnit())
      u.rst_n := slowRst_n
      u.clk   := slowClk
      val capture = Reg(UInt(8), init = 0)
      capture := u.q
      out := capture
    assert(verdict(new M).isLeft, "the connection may be written in either order")

  // ---- what must not change ---------------------------------------------

  test("with no explicit connection R13.2's auto-connection is unchanged"):
    class M extends TwoClocks:
      val out = Out(UInt(8))
      val u = inst(new SlowUnit())      // takes `fast`, and stays there
      val capture = Reg(UInt(8), init = 0)
      capture := u.q                    // same domain — not a crossing
      out := capture
    val top = verdict(new M).getOrElse(fail("should elaborate: one domain"))
    val net = top.signals.find(_.name == "u_q").getOrElse(fail("no u_q net"))
    assertEquals(net.domain.clkName, "clk")

  test("a re-tagged instance still emits the connection it was given"):
    class M extends TwoClocks:
      val out = Out(UInt(8))
      val u = inst(new SlowUnit())
      u.clk   := slowClk
      u.rst_n := slowRst_n
      out := unsafeCrossDomain(u.q, "test fixture: the crossing is the point")
    val top = verdict(new M).getOrElse(fail("blessed crossing should elaborate"))
    val v = new VerilogEmitter("0.1.1").emitModule(top, "M")
    assert(v.contains(".clk(slowClk)"), s"the pin keeps its connection: $v")
    assert(v.contains(".rst_n(slowRst_n)"), s"and so does the reset: $v")
    assertEquals(top.signals.find(_.name == "u_q").map(_.domain.clkName), Some("slowClk"))

  test("a child with two input clocks keeps the enclosing domain"):
    class DualUnit extends Module:
      val clkA   = In(Clock())
      val clkB   = In(Clock())
      val rstA_n = In(Reset(ActiveLow))
      given a: ClockDomain = ClockDomain(clkA, rstA_n, ResetKind.Async, ActiveLow)
      val q = Out(UInt(8))
      val cnt = Reg(UInt(8), init = 0)
      cnt := cnt + 1
      q := cnt
    class M extends TwoClocks:
      val out = Out(UInt(8))
      val u = inst(new DualUnit())
      u.clkA := slowClk
      u.clkB := clk
      val capture = Reg(UInt(8), init = 0)
      capture := u.q
      out := capture
    // Two clocks, no single answer to which one `q` follows — the parent cannot
    // say, so nothing is guessed and the enclosing domain stands.
    val top = verdict(new M).getOrElse(fail("should elaborate unchanged"))
    assertEquals(top.signals.find(_.name == "u_q").map(_.domain.clkName), Some("clk"))

  // ---- R5.6: a related clock is not a crossing ---------------------------

  test("a child on a clock declared one source with ours is read without a marker"):
    class M extends TwoClocks:
      relatedClocks(clk, slowClk)
      val out = Out(UInt(8))
      val u = inst(new SlowUnit())
      u.clk   := slowClk
      u.rst_n := slowRst_n
      val capture = Reg(UInt(8), init = 0)     // in `fast`
      capture := u.q                           // slowClk data, one family
      out := capture
    val top = verdict(new M).getOrElse(fail("one family, so no crossing"))
    // and the fact is still recorded: the net is the slow clock's, not ours
    assertEquals(top.signals.find(_.name == "u_q").map(_.domain.clkName), Some("slowClk"))
