package scalahdl.examples

import java.nio.file.{Files, Path, Paths}

import scalahdl.diag.{ExcerptSource, TraceConfig, ValidationFailed}
import scalahdl.elab.Module
import scalahdl.emit.Emission

/** Command-line entry point that elaborates any no-argument ScalaHDL module and
  * writes its Verilog files and manifest to an output directory.
  *
  * H1 — when a design does not validate, the diagnostic is printed with the
  * R19 excerpt/caret block, not the bare `-->` location. That needs an
  * `ExcerptSource` pointed at the directory the design's source files live in,
  * because `Loc.file` carries a bare file name (the R1 macro captures
  * `sourceFile.name`). Scala 3 records no source path on a class, so the
  * generator cannot read its own source directory at run time; the root is
  * resolved in two steps instead — see `excerptSourceFor`. */
object Emit:

  /** The examples source root in the documented repository layout, relative to
    * the directory the generator runs from. Not absolute and not
    * machine-specific: it is the documented layout, the same kind of path the
    * test suites use. */
  private val repoLayoutRoot = Paths.get("examples/src/main/scala/scalahdl/examples")

  /** The directory the generator was started from, which both resolution steps
    * are relative to. */
  private val workingDir: Path = Paths.get("").toAbsolutePath

  /** Resolve the excerpt source for a design whose `Loc.file` is `name`.
    *
    * Step 1 (unchanged) joins the documented repository layout to `base`. That
    * is what a run from the repository root needs, and it stays first so that
    * run is not disturbed.
    *
    * Step 2 is the graceful fallback. `Loc.file` is a bare file name, so a run
    * started from anywhere else finds nothing under step 1's directory and the
    * caret block silently disappears — the gap this resolves. The fallback
    * searches `base` for the one file of that name; a search, not a second
    * join, is the honest step because a bare name carries no directory, and the
    * only search-free alternatives — the generator's compiled-class location,
    * say — assume a build-output layout no more reliable than step 1 and one
    * that does not hold for a source-dependency consumer. The walk never leaves
    * `base` and stops after `searchDepth` levels, so it is a bounded
    * neighbourhood rather than a sweep of the filesystem, and a name found in
    * more than one place is left unresolved rather than guessed (a wrong
    * excerpt misleads more than no excerpt). Exposed so a test can drive the
    * same resolution without a JVM exit. */
  private[examples] def excerptSourceFor(name: String, base: Path): ExcerptSource =
    val primary = base.resolve(repoLayoutRoot)
    if Files.exists(primary.resolve(name)) then ExcerptSource.fromDir(primary)
    else
      locate(name, base) match
        case Some(dir) => ExcerptSource.fromDir(dir)
        case None      => ExcerptSource.none

  /** How deep the fallback looks below `base`. Eight levels reach the file from
    * the repository root's parent, where it sits eight levels down, without
    * turning the walk into a filesystem-wide search. */
  private val searchDepth = 8

  /** The directory holding the sole file named `name` below `base`: `None` if
    * there is no such file, more than one, or a subtree that cannot be read —
    * failing to find an excerpt must never break emission. */
  private def locate(name: String, base: Path): Option[Path] =
    if !Files.isDirectory(base) then None
    else
      val stream = Files.walk(base, searchDepth)
      try
        var matchDir: Option[Path] = None
        var ambiguous = false
        val it = stream.iterator()
        while it.hasNext && !ambiguous do
          val p = it.next()
          if Files.isRegularFile(p) && p.getFileName.toString == name then
            if matchDir.isEmpty then matchDir = Some(p.getParent) else ambiguous = true
        if ambiguous then None else matchDir
      catch case _: Exception => None
      finally stream.close()

  def main(args: Array[String]): Unit =
    if args.length != 2 then
      System.err.println(
        "Usage: scalahdl.examples.Emit <fully-qualified-module-class> <output-directory>"
      )
      sys.exit(2)

    val className = args(0)
    val outputDir = args(1)
    try
      val module = instantiate(className)
      val design = module.elaborateDesign()
      val emission = new Emission()
      val emitted = emission.emit(design)
      emission.writeTo(emitted, outputDir)

      emitted.files.foreach { case (name, _) => println(s"Wrote $outputDir/$name") }
      println(s"Wrote $outputDir/manifest.f")
    catch
      case e: ValidationFailed =>
        // H2 — a rejected design is a diagnostic, not a Java stack trace: print
        // the rendered block (with the excerpt) and exit non-zero.
        System.err.println(rejected(e))
        sys.exit(1)

  /** The message the generator prints for a rejected design — the R19 block
    * rendered against the source root resolved for the failing file, so a first
    * user sees the offending line and a caret under the column that is wrong. */
  private[examples] def rejected(e: ValidationFailed): String =
    val source = e.diagnostics.headOption
      .map(d => excerptSourceFor(d.primaryLoc.file, workingDir))
      .getOrElse(ExcerptSource.none)
    e.fullRender(using source, TraceConfig.filtered)

  private def instantiate(className: String): Module =
    try
      val cls = Class.forName(className)
      if !classOf[Module].isAssignableFrom(cls) then
        throw new IllegalArgumentException(
          s"$className does not extend ${classOf[Module].getName}"
        )

      cls.getDeclaredConstructor().newInstance().asInstanceOf[Module]
    catch
      case _: ClassNotFoundException =>
        throw new IllegalArgumentException(
          s"Top module class not found: $className. It must be on the examples project classpath."
        )
      case _: NoSuchMethodException =>
        throw new IllegalArgumentException(
          s"Top module class $className must have a no-argument constructor."
        )
end Emit
