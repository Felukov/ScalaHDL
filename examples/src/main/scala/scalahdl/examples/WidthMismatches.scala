package scalahdl.examples

import java.nio.file.Paths

import scalahdl.clock.ResetKind
import scalahdl.core.*
import scalahdl.core.ResetPolarity.ActiveHigh
import scalahdl.diag.{Diagnostic, ExcerptSource, ValidationFailed}
import scalahdl.dsl.{*, given}
import scalahdl.elab.Module

/** R06 / R6.5 / R21.7 — a gallery of the ways two operands can disagree, and
  * what each one is reported as.
  *
  * Every module here is *deliberately wrong*, and each is wrong in exactly one
  * way, so the diagnostic it produces is the whole output rather than the first
  * of a cascade. Run it with:
  *
  * {{{
  *   sbt "examples/runMain scalahdl.examples.WidthMismatches"
  * }}}
  *
  * from the repository root — the `ExcerptSource` below resolves `Loc.file`
  * against this directory, so the caret block quotes the very lines printed
  * above it.
  *
  * The three groups answer three different questions, and they take three
  * different paths through `WidthPhase`:
  *
  *   - **UInt vs UInt** — same kind, different width. `checkWidthAgreement`
  *     reports it, and the summary carries *numbers*: both widths and both
  *     types with their widths.
  *   - **UInt vs Bool** — different kind. The signedness check fires first and
  *     returns, so the summary carries *names* and no width is mentioned at
  *     all, even though the widths also differ.
  *   - **Bool vs a literal `100`** — the literal has no width of its own
  *     (R6.4) and takes the one its context offers. Here that context is one
  *     bit, so the error is neither of the above: it is R21.7's over-range
  *     literal, raised in the builder before any width check runs. */
object WidthMismatches:

  private given src: ExcerptSource =
    ExcerptSource.fromDir(Paths.get("examples/src/main/scala/scalahdl/examples"))

  /** The clock, reset and domain every module below needs, so each one's body
    * is only the mistake it is demonstrating. */
  abstract class Fixture extends Module:
    val clk = In(Clock())
    val rst = In(Reset(ActiveHigh))
    given domain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)

  // ---- 1. two UInts of different width ----------------------------------

  /** `===` between `UInt(8)` and `UInt(12)`: same kind, so the operands reach
    * the width-agreement check. */
  class CompareUIntWidths extends Fixture:
    val a  = In(UInt(8))
    val b  = In(UInt(12))
    val eq = Out(Bool())
    eq := (a === b)

  /** The same disagreement at an assignment rather than an operator: the
    * target's width is the one that must be met. */
  class AssignUIntWidths extends Fixture:
    val narrow = In(UInt(8))
    val wide   = Out(UInt(12))
    wide := narrow

  // ---- 2. a UInt and a Bool ---------------------------------------------

  /** `===` between `Bool` and `UInt(8)`. R21 admits `Bool` as a condition, not
    * as a number, so this is a *kind* disagreement and never reaches the width
    * check — the message says so, and says nothing about 1 versus 8. */
  class CompareBoolToUInt extends Fixture:
    val flag = In(Bool())
    val byte = In(UInt(8))
    val eq   = Out(Bool())
    eq := (flag === byte)

  /** The assignment form. Here the order is reversed: widths are checked
    * first, so a `Bool` driving a `UInt(8)` is reported as 1 bit against 8 and
    * the kind never comes up. */
  class AssignBoolToUInt extends Fixture:
    val flag = In(Bool())
    val byte = Out(UInt(8))
    byte := flag

  /** …and at one bit the widths *do* agree, so the same assignment reaches the
    * kind check — which deliberately lets it through (R6.5: `Bool` and a 1-bit
    * unsigned kind are interchangeable at an assignment). Included because a
    * gallery of errors should show where the line actually falls. */
  class AssignBoolToOneBitUInt extends Fixture:
    val flag = In(Bool())
    val bit  = Out(UInt(1))
    bit := flag

  // ---- 3. a Bool and the literal 100 ------------------------------------

  /** `flag === 100`. A bare `Int` carries neither width nor kind (R6.4) and
    * takes both from its context; the context here is a `Bool`, one bit wide,
    * and 100 needs seven. R21.7 catches it in the builder. */
  class CompareBoolToLiteral extends Fixture:
    val flag = In(Bool())
    val eq   = Out(Bool())
    eq := (flag === 100)

  /** The assignment form of the same thing. */
  class AssignLiteralToBool extends Fixture:
    val flag = Out(Bool())
    flag := 100

  /** For contrast: the identical literal against an 8-bit target, where the
    * context it is offered is wide enough and nothing is reported. */
  class AssignLiteralToUInt8 extends Fixture:
    val byte = Out(UInt(8))
    byte := 100

  // ---- the driver -------------------------------------------------------

  private val cases: List[(String, () => Module)] = List(
    "1a. UInt(8) === UInt(12)"     -> (() => new CompareUIntWidths),
    "1b. UInt(12) := UInt(8)"      -> (() => new AssignUIntWidths),
    "2a. Bool === UInt(8)"         -> (() => new CompareBoolToUInt),
    "2b. UInt(8) := Bool"          -> (() => new AssignBoolToUInt),
    "2c. UInt(1) := Bool"          -> (() => new AssignBoolToOneBitUInt),
    "3a. Bool === 100"             -> (() => new CompareBoolToLiteral),
    "3b. Bool := 100"              -> (() => new AssignLiteralToBool),
    "3c. UInt(8) := 100"           -> (() => new AssignLiteralToUInt8))

  def main(args: Array[String]): Unit =
    cases.foreach { case (title, build) =>
      println(s"\n${"=" * 72}\n$title\n${"=" * 72}")
      report(build)
    }

  /** Elaborate one deliberately-wrong module and print what came back.
    *
    * `elaborateDesign` throws `ValidationFailed` carrying every diagnostic a
    * phase produced, so the batch is printed whole; a module that elaborates
    * cleanly says so, which is the interesting answer for `2c` and `3c`. */
  private def report(build: () => Module): Unit =
    try
      build().elaborateDesign()
      println("elaborated with no diagnostics")
    catch
      case e: ValidationFailed =>
        e.diagnostics.foreach(d => println(render(d)))

  private def render(d: Diagnostic): String = d.render
end WidthMismatches
