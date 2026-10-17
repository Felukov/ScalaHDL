package scalahdl.diag

import java.nio.file.{Files, Paths}
import scalahdl.core.Loc

/** R19 golden tests — the rendered shape is the contract. The WIDTH_MISMATCH
  * block renders byte-for-byte against the spec's R19 example (with one
  * deliberate correction: see `specColTypo`).
  *
  * The diagnostic is constructed synthetically against the `Fifo.scala`
  * fixture (read by `ExcerptSource.fromDir`) so the golden pins the *renderer*
  * — bytes, indentation, caret column — against a fixture that cannot drift
  * when the DSL changes. That elaboration now fills the same shape from real
  * source (`:=`, `when` and the rest capture their call site, so the primary
  * location is the line that got it wrong) is `UseSiteLocSuite`'s subject; this
  * suite stays on the renderer. */
class DiagGoldenSuite extends munit.FunSuite:

  private val fixtureDir =
    Paths.get("core/src/test/resources/scalahdl/diag")

  private given src: ExcerptSource = ExcerptSource.fromDir(fixtureDir)

  /** The WIDTH_MISMATCH diagnostic from the spec's R19 example, built against
    * the Fifo.scala fixture (count @ 11:7, threshold @ 12:7, `+` @ 24:16). */
  private val widthMismatch = Diagnostic(
    severity = Severity.Error,
    code = Code.WIDTH_MISMATCH,
    summary = "cannot add UInt(8) and UInt(12)",
    primaryLoc = Loc("Fifo.scala", 24, 16),
    related = List(
      RelatedLoc(Loc("Fifo.scala", 11, 7), "`count` declared here with width 8"),
      RelatedLoc(Loc("Fifo.scala", 12, 7), "`threshold` declared here with width 12")
    ),
    fix = Some("widths must match explicitly\nsum := count.resize(12) + threshold"),
    primaryNote = Some("operands must have equal width")
  )

  // The byte-for-byte golden. This is the spec's R19 block with `24:16` in
  // place of the spec's `24:14`: the spec example draws the caret under the
  // `+` at column 16 but reports `--> 24:14` — a 2-column inconsistency. The
  // renderer is caret-authoritative (caret at Loc.col, `-->` reports the same
  // Loc.col), so the golden uses `24:16`, the column the caret points at.
  // Indentation and spacing are part of the contract; the caret pad is `col`
  // spaces (it includes the separator space after `|`), the `help:`
  // continuation lines are indented 7 spaces, and the gutter `|` aligns under
  // the last digit of the line number + one space.
  private val widthMismatchGolden =
    """error[WIDTH_MISMATCH]: cannot add UInt(8) and UInt(12)
      |  --> Fifo.scala:24:16
      |   |
      |24 |   sum := count + threshold
      |   |                ^ operands must have equal width
      |   |
      |note: `count` declared here with width 8
      |  --> Fifo.scala:11:7
      |note: `threshold` declared here with width 12
      |  --> Fifo.scala:12:7
      |help: widths must match explicitly
      |       sum := count.resize(12) + threshold
      |""".stripMargin

  test("specColTypo: the spec's `24:14` is a 2-col inconsistency with the caret"):
    // The caret is drawn under `+` at column 16, but `-->` says `24:14`. We
    // document and assert the correction here so the golden's `24:16` is not
    // a mystery. See `hdl-spec.md:707-720` and `Fifo.scala:24`.
    val excerpt = src.lineText("Fifo.scala", 24).getOrElse(fail("fixture line 24"))
    assertEquals(excerpt, "  sum := count + threshold")
    val plusCol = excerpt.indexOf('+') + 1   // 1-based
    assertEquals(plusCol, 16, "the `+` is at column 16 in the fixture")
    // The spec reports 14; we report 16 (the caret position).
    assert(plusCol != 14, "if this fails, the fixture moved and the golden needs review")

  test("WIDTH_MISMATCH renders byte-for-byte against the spec R19 block"):
    val rendered = widthMismatch.render
    assertEquals(rendered, widthMismatchGolden,
      s"WIDTH_MISMATCH byte-for-byte mismatch:\n--- rendered ---\n$rendered\n--- golden ---\n$widthMismatchGolden")

  test("a diagnostic with no related location renders (degenerate case)"):
    val d = Diagnostic(
      severity = Severity.Error,
      code = Code.NAME_COLLISION,
      summary = "`x` is declared twice",
      primaryLoc = Loc("Fifo.scala", 11, 7),
      related = Nil,
      fix = Some("rename one of the declarations"))
    val rendered = d.render
    // No `note:` lines; excerpt present (line 11 from the fixture).
    assert(rendered.contains("error[NAME_COLLISION]: `x` is declared twice\n"), s"header: $rendered")
    assert(rendered.contains("  --> Fifo.scala:11:7\n"), s"primary loc: $rendered")
    assert(!rendered.contains("note:"), s"no related note expected: $rendered")
    assert(rendered.contains("help: rename one of the declarations\n"), s"help: $rendered")
    // The excerpt + caret block is present (primaryNote is None → bare caret).
    assert(rendered.contains("11 |       val count = In(UInt(8))\n"), s"excerpt: $rendered")
    assert(rendered.contains("|\n"), s"caret/bare: $rendered")

  test("a diagnostic with NoLoc primary renders header + help only"):
    val d = Diagnostic(
      severity = Severity.Error,
      code = Code.BIT_SELECT_ANONYMOUS,
      summary = "anonymous bit-select has no width",
      primaryLoc = Loc.NoLoc,
      fix = Some("bind the expression to a `val`"))
    val rendered = d.render
    val expected = "error[BIT_SELECT_ANONYMOUS]: anonymous bit-select has no width\nhelp: bind the expression to a `val`\n"
    assertEquals(rendered, expected, s"NoLoc primary should emit header + help only: $rendered")

  test("rendered output is deterministic (same input → same bytes)"):
    val a = widthMismatch.render
    val b = widthMismatch.render
    assertEquals(a, b, "two renders of the same diagnostic must be byte-identical")

  test("Codes are an enum, not magic strings; no ad-hoc codes outside the enum"):
    // The exit criterion: a test greps the codebase to ensure no `code = "..."`
    // string literals remain. `Diagnostic.code` is typed `Code` (enum), so the
    // compiler already rejects ad-hoc strings; this grep is the belt-and-braces
    // check the spec asks for.
    val root = Paths.get("core/src/main/scala/scalahdl")
    val offenders = scala.collection.mutable.ListBuffer.empty[String]
    val walk = new java.util.ArrayList[String]()
    def scan(dir: java.nio.file.Path): Unit =
      Files.walk(dir).forEach { p =>
        if Files.isRegularFile(p) && p.toString.endsWith(".scala") then
          val text = Files.readString(p)
          // Flag `code = "..."` (string-literal code) — the enum form is `code = Code.X`.
          val re = """code\s*=\s*"[A-Z_]+""""
          if re.r.findFirstIn(text).isDefined then
            offenders += p.toString
      }
    scan(root)
    assert(offenders.isEmpty,
      s"ad-hoc string codes found (use `Code.X` enum instead): ${offenders.mkString(", ")}")
