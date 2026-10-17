package scalahdl.diag

import scalahdl.core.Loc

/** R19 — Structured diagnostics. The error text *is* much of the teaching
  * surface, so diagnostics are a feature rather than a mechanism.
  *
  * Every diagnostic carries:
  *   - a **stable code** (`WIDTH_MISMATCH`) — linkable to documentation,
  *     testable without matching prose
  *   - a one-line summary
  *   - the **primary location** with source excerpt
  *   - **related locations** with their own excerpts
  *   - a **suggested fix as code**, where mechanical
  *
  * Two locations matter and they differ: where a signal was *declared* and
  * where it was *used wrongly*. Most HDLs report only one; this carries both.
  *
  * The renderer writes the R19 excerpt/caret/`note:`/`help:` block (see
  * `Renderer`). `primaryNote` is the caret annotation, which the spec block
  * carries separately from the summary; R19.1 adds the stack trace and the
  * elaboration path. */

/** Severity. `Warning` is for R5.1's unsafe-crossing report and R16.1's
  * non-contributing-parameter note. */
enum Severity:
  case Error, Warning
  def label: String = this match
    case Error   => "error"
    case Warning => "warning"

/** A location with a one-line relation note ("declared here with width 8").
  * `note` is the relation; the location is the fact. */
final case class RelatedLoc(loc: Loc, note: String)

/** The diagnostic. Immutable; built by the validation phases and rendered by
  * `Diagnostic.render` (the excerpt/caret form via `Renderer`).
  *
  * `primaryNote` is the caret annotation drawn under the caret on the primary
  * excerpt line (the spec's `^ operands must have equal width`); it is distinct
  * from `summary` (the header line). `None` ⇒ a bare caret with no trailing
  * text. Related locations carry their own `note` on `RelatedLoc`. */
final case class Diagnostic(
  severity: Severity,
  code: Code,
  summary: String,
  primaryLoc: Loc,
  related: List[RelatedLoc] = Nil,
  fix: Option[String] = None,
  primaryNote: Option[String] = None
):
  /** Human-readable render — the R19 excerpt/caret block. Excerpt text comes
    * from the `given ExcerptSource`; with the default (`ExcerptSource.none`)
    * the `-->` location is emitted without the excerpt block. Deterministic:
    * the same input yields the same bytes. */
  def render(using src: ExcerptSource = ExcerptSource.none): String =
    Renderer.render(this)

/** All errors collected by a validation run across a single phase (R17:
  * errors within a phase batch). Thrown by `Module.elaborate` after the
  * builder freezes if any phase produced errors.
  *
  * R19.1 — carries the captured Scala stack (error path only) and the
  * elaboration path, rendered as the `in:` / `at:` block by `fullRender`. The
  * default `getMessage` uses `ExcerptSource.none` and the ambient
  * `TraceConfig` so an uncaught exception never does file I/O; `fullRender`
  * lets a caller supply an excerpt source (the example generator points it at
  * the source root, so the caret block a first user needs renders). */
final case class ValidationFailed(
  diagnostics: List[Diagnostic],
  stack: List[StackFrame] = Nil,
  elabPath: String = ""
) extends RuntimeException(ValidationFailed.renderMessage(diagnostics, stack, elabPath)):
    override def toString: String = getMessage

    /** Re-render this failure with an excerpt source in scope, so the R19
      * excerpt/caret block appears. The constructor's `getMessage` deliberately
      * uses `ExcerptSource.none` (an uncaught exception never does file I/O);
      * a caller that knows the source root calls this instead. */
    def fullRender(using src: ExcerptSource, cfg: TraceConfig): String =
      ValidationFailed.renderMessage(diagnostics, stack, elabPath)

object ValidationFailed:
  /** Convenience for a single diagnostic, with no trace or path. */
  def one(d: Diagnostic): ValidationFailed = ValidationFailed(List(d))

  /** The default message: diagnostics (no excerpts) + the `in:`/`at:` block.
    * No file I/O — uses `ExcerptSource.none`. */
  private def renderMessage(diags: List[Diagnostic], stack: List[StackFrame],
                            elabPath: String)(using
      src: ExcerptSource = ExcerptSource.none,
      cfg: TraceConfig = TraceConfig.filtered): String =
    val sb = new StringBuilder
    sb.append("validation failed:\n")
    diags.foreach(d => sb.append(d.render).append('\n'))
    Trace.renderBlock(stack, elabPath, sb)
    sb.toString
