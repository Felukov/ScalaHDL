package scalahdl.diag

/** R19.1 — Filtered Scala stack traces + the elaboration path, rendered as
  * the `in:` / `at:` block:
  *
  * ```
  * in: top.rxPath.fifo (AsyncFifo_d16)
  * at: MyDesign.buildChannel(MyDesign.scala:47)
  *     MyDesign.<init>(MyDesign.scala:31)
  * ```
  *
  * Capture happens on the **error path only** — `Validator.validate` calls
  * `Trace.capture()` at the point it is about to throw `ValidationFailed`;
  * successful elaboration never pays for it. Filtering drops library/JVM
  * frames by default and keeps user/generator frames; `TraceConfig.fullTraces`
  * restores everything for debugging the library itself.
  *
  * **Limitation:** validation runs in `Module.elaborate()` *after* the
  * generator constructor has returned, so the generator's `<init>` frame is
  * generally no longer on the stack at capture time, and the captured trace
  * starts at the `elaborate` call site (a user frame). The `in:` elaboration
  * path — not the trace — is the disambiguator when the same generator is
  * called from two sites (per spec); it names the module once, as
  * `in: Mod (Mod)`. */

/** One frame of a captured stack. Raw JVM text — Scala 3 name demangling is
  * explicitly a non-goal (the spec accepts the raw trace). */
final case class StackFrame(className: String, methodName: String,
                           fileName: String, line: Int):
  /** `className.methodName(fileName:line)`, matching the spec's `at:` form. */
  def render: String = s"$className.$methodName($fileName:$line)"

/** Filter configuration. `fullTraces = false` (default) drops library/JVM
  * frames; `true` keeps everything. The flag is a contextual
  * `given TraceConfig` that tests or a tool driver set. */
final case class TraceConfig(fullTraces: Boolean)

object TraceConfig:
  /** Default: filtered (library/JVM frames dropped). */
  given filtered: TraceConfig = TraceConfig(fullTraces = false)

object Trace:

  /** Capture the current thread's stack as a list of frames. Called only on
    * the error path. The leading JVM `getStackTrace` housekeeping frames
    * (`java.lang.Thread`, ` dalvik`/`java.lang.Thread.getStackTrace`) are
    * dropped here unconditionally — they are never user-relevant. */
  def capture(): List[StackFrame] =
    val raw = Thread.currentThread.nn.getStackTrace.nn.iterator.map(toFrame).toList
    // Drop the Thread.getStackTrace housekeeping frames at the top.
    raw.dropWhile(f => f.className == "java.lang.Thread" || f.methodName == "getStackTrace")

  /** Filter a captured stack per `cfg`. Library frames (whose class starts
    * with `scalahdl.`, `java.`, `sun.`, `jdk.`, or `scala.` runtime) are
    * dropped unless `fullTraces` is set; user/generator frames are kept. */
  def filter(stack: List[StackFrame])(using cfg: TraceConfig): List[StackFrame] =
    if cfg.fullTraces then stack else stack.filterNot(isLibrary)

  /** Render the `in:` / `at:` block into `sb` (no leading separator; each
    * line ends with `\n`). Omitted entirely when both path and stack are
    * empty. */
  def renderBlock(stack: List[StackFrame], elabPath: String,
                  sb: StringBuilder)(using cfg: TraceConfig): Unit =
    val frames = filter(stack)
    if elabPath.isEmpty && frames.isEmpty then return
    if elabPath.nonEmpty then sb.append(s"in: $elabPath\n")
    if frames.nonEmpty then
      sb.append(s"at: ${frames.head.render}\n")
      frames.tail.foreach(f => sb.append(s"    ${f.render}\n"))

  // ---- internals ---------------------------------------------------------

  private def toFrame(e: StackTraceElement): StackFrame =
    StackFrame(e.getClassName.nn, e.getMethodName.nn,
               e.getFileName.nn, e.getLineNumber)

  private val libPrefixes = Seq("scalahdl.", "java.", "sun.", "jdk.", "scala.")

  private def isLibrary(f: StackFrame): Boolean =
    libPrefixes.exists(p => f.className.startsWith(p))
end Trace
