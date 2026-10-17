package scalahdl.clock

import scalahdl.dsl.{*, given}
import scalahdl.clock.ResetKind
import scalahdl.core.ResetPolarity.{ActiveHigh, ActiveLow}
import scalahdl.diag.{Code, ValidationFailed}
import scalahdl.elab.Module

/** R4.2 / R4.3 — a clock region is a statement about declarations.
  *
  * A domain belongs to declared hardware. `when` and `:=` take no
  * `ClockDomain` at all, so a region wrapped round statements alone governs
  * nothing — it reads as a claim and is not one (R4.2). And where a region is
  * open it is consulted *before* the lexical given, so a `given` written
  * inside a `withClock` used to be dropped in silence and the register took
  * the region's reset shape rather than the one at the declaration (R4.3).
  *
  * Both were found in plustoo-soc's `dataController`, which named its reset
  * domain twice — once on the register, where it counted, and once round the
  * `when` that decrements it, where it did not.
  *
  * Each test maps to a checkbox in
  * `roadmap/open/M36-domain-scope/`. */
class DomainScopeSuite extends munit.FunSuite:

  abstract class TwoDomains extends Module:
    val clk       = In(Clock())
    val rst_n     = In(Reset(ActiveLow))
    val slowClk   = In(Clock())
    val slowRst_n = In(Reset(ActiveLow))
    given fast: ClockDomain = ClockDomain(clk, rst_n, ResetKind.Async, ActiveLow)
    lazy val slow: ClockDomain = ClockDomain(slowClk, slowRst_n, ResetKind.Async, ActiveLow)

  private def codeOf(m: => Module): Code =
    intercept[ValidationFailed](m.elaborateDesign()).diagnostics.head.code

  private def domainOf(m: scalahdl.core.Module, name: String): ClockDomain =
    m.signals.find(_.name == name)
      .getOrElse(fail(s"no signal named `$name` in ${m.signals.map(_.name)}"))
      .domain

  // ---- R4.2 — the region must govern a declaration ----------------------

  test("a region holding only statements is refused"):
    class M extends TwoDomains:
      val count = Reg(UInt(8), init = 0, domain = slow)
      withClock(slow):
        count := count + 1
    assertEquals(codeOf(new M), Code.DOMAIN_SCOPE_EMPTY)

  test("the diagnostic names the clock and offers the argument instead"):
    class M extends TwoDomains:
      val count = Reg(UInt(8), init = 0, domain = slow)
      withClock(slow):
        count := count + 1
    val d = intercept[ValidationFailed](new M().elaborateDesign()).diagnostics.head
    assert(d.summary.contains("slowClk"), d.summary)
    assert(d.fix.exists(_.contains("domain = d")), d.fix.toString)

  test("one declaration is enough, and its neighbours may be statements"):
    class M extends TwoDomains:
      val q = Out(UInt(8))
      withClock(slow):
        val count = Reg(UInt(8), init = 0)
        count := count + 1
        q := count
    val top = (new M).elaborateDesign().top
    assertEquals(domainOf(top, "count").clkName, "slowClk")

  test("a declaration in a nested region counts for the outer one too"):
    class M extends TwoDomains:
      val q = Out(UInt(8))
      val third    = In(Clock())
      val thirdRst = In(Reset(ActiveHigh))
      lazy val other: ClockDomain = ClockDomain(third, thirdRst, ResetKind.Sync, ActiveHigh)
      withClock(slow):
        withClock(other):
          val count = Reg(UInt(8), init = 0)
          count := count + 1
          q := count
    val top = (new M).elaborateDesign().top
    assertEquals(domainOf(top, "count").clkName, "third")

  test("a body that throws reports its own error, not R4.2's"):
    class M extends TwoDomains:
      withClock(slow):
        val a = Reg(UInt(8), init = 0)
        val b = Reg(UInt(4), init = 0)
        a := b + 1.U(9)     // a width error inside the region
    assert(codeOf(new M) != Code.DOMAIN_SCOPE_EMPTY, "the region's own error must survive")

  // ---- R4.3 — the region is not overridden by a lexical given ------------

  test("a given written inside a region is refused rather than dropped"):
    class M extends TwoDomains:
      val third    = In(Clock())
      val thirdRst = In(Reset(ActiveHigh))
      lazy val other: ClockDomain = ClockDomain(third, thirdRst, ResetKind.Sync, ActiveHigh)
      val q = Out(UInt(8))
      withClock(slow):
        locally:
          given ClockDomain = other
          val count = Reg(UInt(8), init = 0)
          count := count + 1
          q := count
    assertEquals(codeOf(new M), Code.DOMAIN_SCOPE_SHADOWED)

  test("the diagnostic names both domains — the one asked for and the one that wins"):
    class M extends TwoDomains:
      val third    = In(Clock())
      val thirdRst = In(Reset(ActiveHigh))
      lazy val other: ClockDomain = ClockDomain(third, thirdRst, ResetKind.Sync, ActiveHigh)
      val q = Out(UInt(8))
      withClock(slow):
        locally:
          given ClockDomain = other
          val count = Reg(UInt(8), init = 0)
          count := count + 1
          q := count
    val d = intercept[ValidationFailed](new M().elaborateDesign()).diagnostics.head
    assert(d.summary.contains("third"), d.summary)
    assert(d.summary.contains("slowClk"), d.summary)

  // ---- what R4.3 must not break -----------------------------------------

  test("a helper closing over the module default still lands in the region"):
    class M extends TwoDomains:
      val q = Out(UInt(8))
      // No `using ClockDomain` of its own: the given is resolved at the
      // definition site, so this carries `fast` into a region naming `slow`.
      // R13.2 says the region wins, and it still does.
      private def counter() =
        val c = Reg(UInt(8), init = 0)   // R2: a declaration is bound to a val
        c
      withClock(slow):
        val count = counter()
        count := count + 1
        q := count
    val top = (new M).elaborateDesign().top
    assertEquals(domainOf(top, "c").clkName, "slowClk")

  test("the given-block form is untouched where no region is open (R4.1)"):
    class M extends TwoDomains:
      val q = Out(UInt(8))
      val count = { given ClockDomain = slow; Reg(UInt(8), init = 0) }
      count := count + 1
      q := count
    val top = (new M).elaborateDesign().top
    assertEquals(domainOf(top, "count").clkName, "slowClk")

  test("the argument still wins inside a region, and is not shadowing"):
    class M extends TwoDomains:
      val q = Out(UInt(8))
      val third    = In(Clock())
      val thirdRst = In(Reset(ActiveHigh))
      lazy val other: ClockDomain = ClockDomain(third, thirdRst, ResetKind.Sync, ActiveHigh)
      withClock(other):
        val named = Reg(UInt(8), init = 0, domain = slow)
        val quiet = Reg(UInt(8), init = 0)
        named := named + 1
        quiet := quiet + 1
        q := quiet
    val top = (new M).elaborateDesign().top
    assertEquals(domainOf(top, "named").clkName, "slowClk")
    assertEquals(domainOf(top, "quiet").clkName, "third")
