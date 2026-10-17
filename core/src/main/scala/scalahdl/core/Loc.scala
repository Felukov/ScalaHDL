package scalahdl.core

/** A source location captured at compile time by the R1 macro and carried on
  * every IR node (signals, statements, expressions, instances), feeding R19.
  *
  * `NoLoc` marks nodes that have no source position (synthetic, or anonymous
  * expressions whose owner is a compiler-generated symbol). R2 anonymous trees
  * are never declared, so a missing `Loc` on an expression is legitimate.
  */
final case class Loc(file: String, line: Int, col: Int):
  override def toString: String = s"$file:$line:$col"

object Loc:
  val NoLoc: Loc = Loc("<none>", 0, 0)

  /** The position of the call being inlined — the *statement's* location,
    * which `Source.capture` cannot give because a statement has no binding
    * `val` to own the splice (R2: its owner is a synthetic `<local ...>`
    * symbol).
    *
    * R22 needs it: a duplicate case value is reported at the later `is(...)`
    * and related to the first, and neither is a declaration. Reached through
    * any chain of `inline def`s, so `switch`/`is`/`default` capture the user's
    * call site rather than the DSL's own source. */
  inline def here: Loc = ${ hereImpl }

  def hereImpl(using q: scala.quoted.Quotes): scala.quoted.Expr[Loc] =
    import q.reflect.*
    val loc =
      try
        val pos = Position.ofMacroExpansion
        Loc(pos.sourceFile.name, pos.startLine + 1, pos.startColumn + 1)
      catch case _: Throwable => NoLoc
    '{ Loc(${ scala.quoted.Expr(loc.file) }, ${ scala.quoted.Expr(loc.line) },
           ${ scala.quoted.Expr(loc.col) }) }

  /** Read by the R1 macro; throws only if the owner genuinely has a position
    * object that cannot be queried (synthetic symbols). Callers wrap in try.
    */
  def fromSymbolPos(using q: scala.quoted.Quotes)(sym: q.reflect.Symbol): Loc =
    import q.reflect.*
    sym.pos match
      case Some(p) => Loc(p.sourceFile.name, p.startLine + 1, p.startColumn + 1)
      case None    => NoLoc
