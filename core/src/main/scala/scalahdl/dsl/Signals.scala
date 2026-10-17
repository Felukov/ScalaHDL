package scalahdl.dsl

import scalahdl.core.*
import scalahdl.elab.Module
import scalahdl.clock.ClockDomain

/** R2 / R6 — Signals, literals, and operators.
  *
  * `Reg` is a named declaration (R1 capture, R13 reset value). Literals and
  * operators produce *anonymous* `Expr` trees (R2: no identity, never
  * declared, inlined at use). Widths are values (R6); a literal without an
  * explicit width takes one from its position (R6.1–R6.5).
  *
  * Operators are extension methods on `DslSignal` so `a + b` reads as Scala. */

/** `Reg(t, init = v)` — declare a register. The reset *value* is here (R13);
  * reset *style* comes from the `using ClockDomain` (R13.1). `Source.capture`
  * is passed directly so the R1 macro's
  * splice owner resolves to the user's binding `val`.
  *
  * R13.2 — the domain is resolved through `activeDomain`: a `withClock`
  * block overrides it; otherwise the `using ClockDomain` default applies.
  *
  * R4.1 — `domain =` names it at the declaration, and wins over both. Omitted,
  * nothing about the resolution changes. The resolving happens *here*, in the
  * one call the user wrote, so `RegElab` receives the domain the register is
  * in rather than a default to re-resolve.
  *
  * R07: accepts a scalar `Type` or a `Bundle`. A `Bundle` argument's
  * field leaves bind to the register, so `r.addr` resolves `addr` as a real
  * symbol and yields a `DslSignal` sub-handle. */
inline def Reg[T](t: T, init: Expr = null, powerUp: Expr = null,
                  domain: ClockDomain = null)(
    using m: Module, cd: ClockDomain, e: RegElab[T]): e.Out =
  e.reg(t, init, powerUp, Source.capture, m, m.builder.declaredDomain(domain, cd))

/** Elaboration of a `Reg` argument: a scalar `Type` yields a plain `DslSignal`;
  * a `Bundle` yields the bundle with its field leaves bound to the register.
  *
  * `domain` is already resolved (R4.1): the declaration's own argument if it
  * named one, else the active domain. */
trait RegElab[T]:
  /** The elaborated handle (`DslSignal` for a scalar, `B` for a bundle). */
  type Out
  def reg(t: T, init: Expr, powerUp: Expr, src: Source, m: Module,
          domain: ClockDomain): Out
object RegElab:
  given RegElab[Type] with
    type Out = DslSignal
    def reg(t: Type, init: Expr, powerUp: Expr, src: Source, m: Module,
            domain: ClockDomain): DslSignal =
      m.builder.addReg(src, t, Option(init), domain, powerUp = Option(powerUp))
  /** R24 — `Reg(AluOp(), init = AluOp.Add)`. The register is a plain `UInt` of
    * the encoding's width; the encoding rides along for the diagnostics. */
  given RegElab[Encoding] with
    type Out = DslSignal
    def reg(e: Encoding, init: Expr, powerUp: Expr, src: Source, m: Module,
            domain: ClockDomain): DslSignal =
      m.builder.addReg(src, e.toType, Option(init), domain, Some(e.info), Option(powerUp))
  given [B <: Bundle]: RegElab[B] with
    type Out = B
    def reg(b: B, init: Expr, powerUp: Expr, src: Source, m: Module,
            domain: ClockDomain): B =
      Bundle.bind(m.builder.addReg(src, b.toType, Option(init), domain,
        powerUp = Option(powerUp)), b)
  given [B <: Bundle]: RegElab[BundleVec[B]] with
    type Out = BundleVecSignal[B]
    def reg(v: BundleVec[B], init: Expr, powerUp: Expr, src: Source, m: Module,
            domain: ClockDomain): BundleVecSignal[B] =
      new BundleVecSignal(m.builder.addReg(src, v.toType, Option(init), domain,
        powerUp = Option(powerUp)), v)

/** `Wire(t)` — declare an internal combinational wire (R2: only named vals get
  * a declaration). Used by library helpers that need a named intermediate —
  * `Mux`, `decode` — where the user did not write a `val`. The name is captured
  * by R1; the domain is the active one (`withClock` overrides).
  *
  * R14 — the argument is elaborated through `WireElab`, exactly as `Reg`'s is
  * through `RegElab`, so a wire accepts every shape a register does: a scalar
  * `Type`, an `Encoding`, a `Bundle`, or a `Vec` of bundles. Aggregates are not
  * a register-only feature — R14.2 *contrasts* the combinational Vec against
  * the `Reg` one, which presupposes it exists — and the layers below already
  * knew this: `VecWritePhase` rejects only the *runtime*-index write, R11
  * checks a combinational aggregate leaf by leaf, and R14.1's struct-of-arrays
  * lowering is chosen by `In`/`Out`, not by `Reg`/`Wire`.
  *
  * Without the typeclass a wire took only `Type`, and the `BundleVec → Type`
  * and `Bundle → Type` conversions made that *silently* lossy rather than a
  * compile error: `Wire(Vec(4, Req(8)))` dropped the element factory and
  * returned a bare `DslSignal`, so `entries(2).addr` failed with `addr` is not
  * a member of `DslSignal` — pointing at the field, which was fine, rather than
  * at the declaration, which was not.
  *
  * R4.1 — `domain =` names the domain at the declaration, and wins over both a
  * `withClock` scope and the `using` default. A wire is combinational, so what
  * its domain decides is R5: which reads of it are a crossing. */
inline def Wire[T](t: T, domain: ClockDomain = null)(
    using m: Module, cd: ClockDomain, e: WireElab[T]): e.Out =
  e.wire(t, Source.capture, m, m.builder.declaredDomain(domain, cd))

/** Elaboration of a `Wire` argument. The mirror of `RegElab`: same four shapes,
  * same `Out` for each, minus the reset value a wire has no place for, and the
  * same already-resolved `domain` (R4.1). */
trait WireElab[T]:
  /** The elaborated handle (`DslSignal` for a scalar, `B` for a bundle). */
  type Out
  def wire(t: T, src: Source, m: Module, domain: ClockDomain): Out
object WireElab:
  given WireElab[Type] with
    type Out = DslSignal
    def wire(t: Type, src: Source, m: Module, domain: ClockDomain): DslSignal =
      m.builder.addWire(src, t, domain)
  /** R24 — `Wire(AluOp())`, a wire carrying a named encoding. The declared type
    * is the `UInt` the encoding stands for; the encoding is recorded alongside
    * so R11/R22 diagnostics about this signal can name its members. */
  given WireElab[Encoding] with
    type Out = DslSignal
    def wire(e: Encoding, src: Source, m: Module, domain: ClockDomain): DslSignal =
      m.builder.addWire(src, e.toType, domain, Some(e.info))
  given [B <: Bundle]: WireElab[B] with
    type Out = B
    def wire(b: B, src: Source, m: Module, domain: ClockDomain): B =
      Bundle.bind(m.builder.addWire(src, b.toType, domain), b)
  given [B <: Bundle]: WireElab[BundleVec[B]] with
    type Out = BundleVecSignal[B]
    def wire(v: BundleVec[B], src: Source, m: Module,
             domain: ClockDomain): BundleVecSignal[B] =
      new BundleVecSignal(m.builder.addWire(src, v.toType, domain), v)

/** Anonymous literal construction. `1.U(8)` → an unsigned literal of width 8.
  * `1.U` (no width) defers to R6.1 context-width inference: inside a binary
  * op the literal inherits the other operand's width; at an assignment
  * target it is *not* inferred from the target (the assignment-boundary
  * guard — widening happens where the user wrote `resize`, never silently
  * through a literal). A width of 0 means "unknown, infer from context". */
extension (n: Int)
  def U: Expr                = Expr.Lit(BigInt(n), None, Some(LitKind.Unsigned))
  def U(width: Int): Expr    = Expr.Lit(BigInt(n), if width <= 0 then None else Some(width), Some(LitKind.Unsigned))
  def S: Expr                = Expr.Lit(BigInt(n), None, Some(LitKind.Signed))
  def S(width: Int): Expr    = Expr.Lit(BigInt(n), if width <= 0 then None else Some(width), Some(LitKind.Signed))

/** R6.4 — a bare `Int` is a constant with neither width nor kind of its own,
  * taking both from the context it appears in: `w := 2`, `funct3 === 5`,
  * `is(2)`, `Reg(UInt(8), init = 0)`.
  *
  * This is the one position where a literal's *kind* is inferred, and it is
  * safe for the reason its width is: a constant's value means the same thing
  * in every kind it can land in. What cannot be represented is still rejected —
  * a negative value against an unsigned context stays R21.7's error — and `.U`
  * and `.S` continue to fix the kind at the literal wherever the context should
  * not decide it.
  *
  * It is also the only spelling that reaches a `Bits` or `Bool` context, which
  * have no literal suffix: `mask === 5` and `flag === 1` had no working form
  * before this. */
given Conversion[Int, Expr] = n => Expr.Lit(BigInt(n), None, None)

/** R6.6 — the one-bit constants, named. A `Bool` holds a condition rather than
  * a number, so the constants it takes are written as conditions:
  * `memWrite := True`, `Reg(Bool(), init = False)`.
  *
  * These are literals, not a new type or a new IR node: kind `Bool` and width
  * 1, both fixed at the literal exactly as `.U` fixes a kind. `x := True`
  * emits the `1'b1` that `x := 1` already emitted, and every rule that governs
  * a literal governs these.
  *
  * The width is fixed rather than inferred, which is the one thing here that
  * had to be decided. Left open it would be taken from the target, and
  * `word := True` on a `UInt(8)` would emit `8'b1` — R6.5's Bool/one-bit
  * exception passes the kind, so nothing else would catch it. Fixed at one bit
  * that assignment is R6's width error, while `Reg(UInt(1), init = False)`
  * still holds, since a single bit is what the exception was written for.
  *
  * Capitalised because `true` and `false` are Scala keywords, and *only*
  * because of that: a `given Conversion[Boolean, Expr]` would admit the
  * lowercase spelling at the cost of turning every accidental Scala-level
  * boolean into a hardware constant — `when(a == b)` on two signals is a
  * compile error today and would silently become `1'b0`. */
val True: Expr  = Expr.Lit(BigInt(1), Some(1), Some(LitKind.Bool))
val False: Expr = Expr.Lit(BigInt(0), Some(1), Some(LitKind.Bool))

/** The operator surface lives in `Operators.scala` (R21): it is defined once
  * on `Expr`, and a `DslSignal` reaches it through the conversion below, so
  * every operator accepts a signal, an expression, or a literal on either side.
  * What remains here is the one rule that must *not* compose — R6.2. */
extension (e: Expr)

  /** R6.2 — bit-select of an anonymous expression is an elaboration error.
    * The IR structurally prevents it (`Slice`/`Index` take a `SignalId`, not
    * an `Expr`), so the only place this can fire is the DSL surface. The fix
    * is to bind the expression to a `val` first. */
  def apply(i: Int): Nothing =
    throw BitSelectAnonymousError("bit-index")
  def apply(hi: Int, lo: Int): Nothing =
    throw BitSelectAnonymousError("bit-slice")

/** R6.2 — The elaboration error thrown by `Expr.apply` (bit-select of an
  * anonymous expression). Rendered as a `BIT_SELECT_ANONYMOUS` diagnostic. */
final case class BitSelectAnonymousError(kind: String)
    extends RuntimeException(s"anonymous $kind requires a named signal: bind the expression to a `val` first")
