package scalahdl.core

/** R12.1 — **which driver blocks are continuous assignments**, asked in one
  * place because two answers would drift.
  *
  * The emitter writes `assign` for these; the validator's `NeverRunsPhase`
  * needs the same answer to know which blocks are still procedural, and so
  * still need something to trigger them.
  */
object ContinuousDriver:

    /** The assignments a combinational block reduces to, or `None` if it is
      * procedural by nature: it is clocked, it has a condition, it writes part
      * of a signal (R6.7 — the other bits must hold), or it writes one leaf
      * twice (a sequence, where two continuous assignments would be two
      * drivers of one net). */
    def assigns(db: DriverBlock): Option[List[Stmt.Assign]] =
        if db.clocked || db.stmts.isEmpty then None
        else
            val assignments = db.stmts.collect { case a: Stmt.Assign => a }
            if assignments.length != db.stmts.length then None
            else
                val keys = assignments.map(a => key(a.target))
                if keys.exists(_.isEmpty) then None
                else
                    val paths = keys.map(_.get)
                    val overlapping =
                        paths.exists(a => paths.exists(b => a != b && b.startsWith(a)))
                    if paths.distinct.length == paths.length && !overlapping then Some(assignments)
                    else None

    /** A target's path as one string, or `None` where it names part of a
      * signal rather than a whole leaf. */
    private def key(lhs: Lhs): Option[String] = lhs match
        case Lhs.Sig(id)          => Some(id.toString)
        case Lhs.Field(base, n)   => key(base).map(_ + "." + n)
        case Lhs.Index(base, idx) => key(base).map(_ + "[" + idx + "]")
        case Lhs.Slice(_, _, _)   => None
        // R34 — a runtime window has no single key: the offset is not a
        // constant, so it cannot name one leaf, exactly as `Index` on a
        // runtime `Vec` index cannot.
        case Lhs.VarSlice(_, _, _) => None
end ContinuousDriver
