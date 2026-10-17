package scalahdl.core

import SignalId.*

/** R07.2 — **an aggregate reads as its packed bits.**
  *
  * A `Bundle` is a packed vector of bits, exactly as a SystemVerilog packed
  * struct is: the first-declared field occupies the most significant bits, and
  * a `Vec` packs descending. What SystemVerilog also has, and Verilog-2001 does
  * not, is a *name* for that vector — a packed struct is one declared object,
  * while R07.1 flattens the same bundle into one wire per leaf. So the whole
  * has no identifier, and every use of it as a value has to be written out as
  * the concatenation of its leaves.
  *
  * That rewrite is this pass. It runs once, on the frozen module, between
  * elaboration and validation, so everything downstream sees an ordinary
  * `Bits` expression: the width checks, the type-admission table, and the
  * emitter need no aggregate case, and `Cat(pair, pair)` emits the four leaf
  * names it means instead of a name no module declares.
  *
  * **Reading packs; writing does not.** A whole-aggregate assignment and a
  * whole-port connection stay structural — one statement over a preserved
  * aggregate (R17), paired leaf by leaf and shape-checked by R14. This is the
  * one place SystemVerilog's model is deliberately not followed: there, two
  * differently-shaped packed structs of equal width assign to each other and
  * silently reinterpret. R14 requires the field shapes to agree, and it can
  * only do that while an aggregate on the *writing* side is still an aggregate.
  *
  * The rule that separates the two is positional and is stated once, here: a
  * path reference to an aggregate stays whole when it is the entire expression
  * being assigned or connected, and packs anywhere inside a larger one.
  *
  * R21's refusal of an aggregate operand survives as a backstop rather than as
  * the rule. Nothing should reach validation as a bare aggregate any more, so
  * if something does, it is a miss in this pass — and it is reported as R07.2's
  * diagnostic instead of emitted as a Verilog identifier that does not exist. */
object AggregateRead:

  /** R07.2's layout, as an expression: a `Cat` of leaf reads, first field in
    * the most significant bits and `Vec` elements descending, typed as the
    * `Bits` it is. The kind is `Bits` rather than `Cat`'s own `UInt` (R21.5)
    * because a packed aggregate is a raw bit pattern; an arithmetic reading is
    * asked for with `.asUInt`, as R21.4 has it asked for everywhere else. */
  def packed(base: Expr, t: Type): Expr =
    Expr.TypeCast(Type.Bits(t.bitWidth), layout(base, t))

  /** The concatenation itself, recursive through nested aggregates. A scalar
    * leaf is its own read. */
  def layout(base: Expr, t: Type): Expr = t match
    case Type.Bundle(fields) =>
      Expr.Cat(fields.map { case (n, ft) => layout(Expr.Field(base, n), ft) })
    case Type.Vec(n, elem) =>
      Expr.Cat((n - 1 to 0 by -1).toList.map { i =>
        layout(Expr.Index(base, Expr.uint(BigInt(i), 32)), elem)
      })
    case _ => base

  def isAggregate(t: Type): Boolean = t match
    case Type.Bundle(_) | Type.Vec(_, _) => true
    case _                               => false

  /** Rewrite every aggregate *read* in a frozen module into its packed bits. */
  def lower(m: Module): Module =
    val types = m.signals.iterator.map(s => s.id -> s.typeOf).toMap
    val typeOf: SignalId => Type = id => types.getOrElse(id, Type.Bool)

    /** A named signal or a selected sub-element of one — the shapes that can
      * still be expanded leaf by leaf. Everything else is already a value. */
    def isPath(e: Expr): Boolean = e match
      case Expr.Ref(_)      => true
      case Expr.Field(b, _) => isPath(b)
      case Expr.Index(b, _) => isPath(b)
      case _                => false

    /** The aggregate type this expression names, if it names one whole. */
    def wholeAggregate(e: Expr): Option[Type] =
      if !isPath(e) then None
      else ExprType.typeOpt(e, typeOf).filter(isAggregate)

    /** An expression in a value position. */
    def value(e: Expr): Expr = wholeAggregate(e) match
      case Some(t) => packed(e, t)
      case None => e match
        case Expr.Ref(_) | Expr.Lit(_, _, _) => e
        // A path that is not an aggregate is already a scalar leaf, and its
        // base is a *path*, not a value: packing inside one would put a
        // concatenation where Verilog-2001 requires a name (R6.2).
        case Expr.Field(_, _)         => e
        case Expr.Index(base, idx)    => Expr.Index(base, value(idx))
        case Expr.Slice(_, _, _)      => e
        case Expr.VarSlice(_, _, _)   => e
        case Expr.UnOp(op, a)         => Expr.UnOp(op, value(a))
        case Expr.BinOp(op, a, b)     => Expr.BinOp(op, value(a), value(b))
        case Expr.Mux(sel, t, f)      => Expr.Mux(value(sel), value(t), value(f))
        case Expr.Cat(parts)          => Expr.Cat(parts.map(value))
        case Expr.Repeat(n, inner)    => Expr.Repeat(n, value(inner))
        case Expr.Resize(to, inner)   => Expr.Resize(to, value(inner))
        case Expr.TypeCast(to, inner) => Expr.TypeCast(to, value(inner))
        case Expr.Cdc(p, inner, loc)  => Expr.Cdc(p, value(inner), loc)

    /** An expression being connected to a port *whole*. A path naming an
      * aggregate stays one, so R07.1 expands it leaf by leaf and R14 checks
      * the two shapes against each other; anything else is a value. */
    def source(e: Expr): Expr =
      if wholeAggregate(e).isDefined then e else value(e)

    /** ...and the same for an assignment, where the target says which of the
      * two this is. Aggregate to aggregate is the structural form. Aggregate
      * to a scalar is a *read* — `bits := pair` is the packed word, which is
      * the whole point of R07.2 — and a scalar to an aggregate stays R14's
      * shape error, because bits become a bundle through `unpackBits` and
      * nowhere else. */
    def assigned(target: Lhs, e: Expr): Expr =
      if isAggregate(ExprType.lhsType(target, typeOf)) then source(e) else value(e)

    def stmt(s: Stmt): Stmt = s match
      case Stmt.Assign(target, expr, blocking, loc) =>
        Stmt.Assign(target, assigned(target, expr), blocking, loc)
      case Stmt.When(cond, thenStmts, elseStmts, loc) =>
        Stmt.When(value(cond), thenStmts.map(stmt), elseStmts.map(stmt), loc)
      case Stmt.Switch(kind, selector, cases, defaults, loc) =>
        Stmt.Switch(kind, value(selector),
          cases.map(c => c.copy(values = c.values.map(value), stmts = c.stmts.map(stmt))),
          defaults.map(_.map(stmt)), loc)
      case Stmt.MemWrite(id, addr, data, loc) =>
        Stmt.MemWrite(id, value(addr), value(data), loc)
      case Stmt.Check(block, cond, message, loc) =>
        Stmt.Check(block, value(cond), message, loc)

    m.copy(
      drivers = m.drivers.map(db => db.copy(stmts = db.stmts.map(stmt))),
      checks = m.checks.map(cb => cb.copy(stmts = cb.stmts.map(stmt))),
      instances = m.instances.map(inst =>
        inst.copy(connections = inst.connections.map((port, e) => (port, source(e))))),
      memories = m.memories.map(mem => mem.copy(
        reads = mem.reads.map(r =>
          r.copy(address = value(r.address), enable = r.enable.map(value))),
        writes = mem.writes.map(w =>
          w.copy(address = value(w.address), data = value(w.data),
                 enable = w.enable.map(value))))))
