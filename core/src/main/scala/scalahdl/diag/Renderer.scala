package scalahdl.diag

import scalahdl.core.Loc

/** R19 — The multi-location diagnostic renderer. Produces the excerpt/caret
  * block the spec shows:
  *
  * ```
  * error[WIDTH_MISMATCH]: cannot add UInt(8) and UInt(12)
  *   --> Fifo.scala:24:16
  *    |
  * 24 |   sum := count + threshold
  *    |                ^ operands must have equal width
  *    |
  * note: `count` declared here with width 8
  *   --> Fifo.scala:11:7
  * note: `threshold` declared here with width 12
  *   --> Fifo.scala:12:7
  * help: widths must match explicitly (R6)
  *        sum := count.resize(12) + threshold
  * ```
  *
  * The caret is **authoritative**: it is drawn at `Loc.col` (1-based), and the
  * `-->` line reports the same `Loc.col`. (The spec example prints `24:14`
  * while drawing the caret under the `+` at column 16 — a 2-column typo; this
  * renderer is self-consistent and uses the column the caret points at.)
  *
  * Degenerate cases:
  *   - `primaryLoc == NoLoc`      → header + help only (no `-->`, no excerpt).
  *   - excerpt unavailable        → header + `--> loc` (no excerpt/caret block).
  *   - `related` empty            → no `note:` blocks.
  *   - `fix == None`              → no `help:` block.
  *   - `primaryNote == None`      → bare caret (`^` with no trailing text).
  *
  * Determinism: the same `Diagnostic` + `ExcerptSource` yield the same bytes,
  * so golden tests are stable across runs. Related locations render as
  * `note:` + `-->` (the spec example carries no excerpts on related locations;
  * R19's "related locations with their own excerpts" is a future enhancement).
  */
object Renderer:

  /** Render one diagnostic. No trailing newline (the caller decides
    * separation); each *line* inside ends with `\n`. */
  def render(d: Diagnostic)(using src: ExcerptSource): String =
    val sb = new StringBuilder
    sb.append(s"${d.severity.label}[${d.code}]: ${d.summary}\n")
    renderPrimary(d, sb)
    d.related.foreach(renderRelated(_, sb))
    d.fix.foreach(renderFix(_, sb))
    sb.toString

  // ---- primary location --------------------------------------------------

  private def renderPrimary(d: Diagnostic, sb: StringBuilder)(using
      src: ExcerptSource): Unit =
    val loc = d.primaryLoc
    if loc == Loc.NoLoc then return
    sb.append(s"  --> $loc\n")
    src.lineText(loc.file, loc.line).foreach { text =>
      val gw = gutterWidth(loc.line)
      val gutter = " " * (gw + 1)   // aligns the `|` under the line number's last digit + space
      sb.append(s"$gutter|\n")
      sb.append(s"${loc.line} | $text\n")
      // The caret pad is `col` spaces (not `col-1`): it includes the single
      // separator space after `|` (which the source line also has), so the
      // caret lands exactly under source column `col`.
      val pad = " " * loc.col
      val note = d.primaryNote.map(n => s" $n").getOrElse("")
      sb.append(s"$gutter|$pad^$note\n")
      sb.append(s"$gutter|\n")
    }

  // ---- related locations -------------------------------------------------

  private def renderRelated(r: RelatedLoc, sb: StringBuilder): Unit =
    sb.append(s"note: ${r.note}\n")
    if r.loc != Loc.NoLoc then sb.append(s"  --> ${r.loc}\n")

  // ---- suggested fix -----------------------------------------------------

  /** `help: <first line>`; subsequent lines indented 7 spaces (matching the
    * spec's `       sum := count.resize(12) + threshold`). */
  private def renderFix(fix: String, sb: StringBuilder): Unit =
    val lines = fix.split("\n", -1)
    sb.append(s"help: ${lines(0)}\n")
    lines.drop(1).foreach { l => sb.append(s"       $l\n") }

  // ---- helpers -----------------------------------------------------------

  /** The width of the line-number gutter (e.g. `24` → 2). */
  private def gutterWidth(line: Int): Int =
    if line <= 0 then 1 else line.toString.length
