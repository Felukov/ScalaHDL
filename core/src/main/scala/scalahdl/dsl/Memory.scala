package scalahdl.dsl

import scalahdl.core.*
import scalahdl.elab.{Builder, Module}
import scalahdl.clock.ClockDomain

/** R15.3 policy names re-exported by the DSL wildcard import. */
val ReadFirst: ReadDuringWrite = ReadDuringWrite.ReadFirst
val WriteFirst: ReadDuringWrite = ReadDuringWrite.WriteFirst
val WriteFirstPort: ReadDuringWrite = ReadDuringWrite.WriteFirstPort
val Undefined: ReadDuringWrite = ReadDuringWrite.Undefined

type MemoryKind = scalahdl.core.MemoryKind

/** R15.4 initial-contents constructor re-exported by the DSL wildcard import. */
type MemoryInit = scalahdl.core.MemoryInit
val HexFile: scalahdl.core.MemoryInit.HexFile.type = scalahdl.core.MemoryInit.HexFile

/** Stable M07 elaboration failure carrying the required diagnostic-code name. */
final case class MemoryElaborationError(code: String, detail: String, loc: Loc)
    extends RuntimeException(s"$code: $detail")

/** R15 — Handle for one explicit memory declaration. Read calls create named
  * read ports; write calls create unconditional synchronous write ports in the
  * active clock domain. */
final class MemoryHandle private[dsl] (
  private val ir: Builder#MemoryBuilder,
  val kind: scalahdl.core.MemoryKind,
  val depth: Int,
  val elemType: Type,
  val init: Option[scalahdl.core.MemoryInit],
  private val module: Module
):
  /** Zero-cycle combinational array read. Block RAM deliberately rejects this
    * API and names both legal asynchronous alternatives. */
  /** Deliberately retained only to turn an omitted policy into the stable M07
    * elaboration diagnostic rather than a generic Scala overload error. */
  def readAsync(address: DslSignal)(using cd: ClockDomain): Nothing =
    policyRequired()
  def readAsync(address: Expr)(using cd: ClockDomain): Nothing =
    policyRequired()

  inline def readAsync(address: DslSignal, policy: ReadDuringWrite)(
      using cd: ClockDomain): DslSignal =
    readAsyncExpr(address.ref, policy, Source.capture)

  inline def readAsync(address: Expr, policy: ReadDuringWrite)(
      using cd: ClockDomain): DslSignal =
    readAsyncExpr(address, policy, Source.capture)

  /** R15.6 — an asynchronous read has no enable, and asking for one is
    * `MEM_READ_ENABLE_ON_ASYNC` rather than a term quietly dropped. The
    * overload exists so that the diagnostic is the one a reader gets. */
  inline def readAsync(address: DslSignal, policy: ReadDuringWrite, enable: DslSignal)(
      using cd: ClockDomain): Nothing =
    enableOnAsync(Source.capture)

  inline def readAsync(address: Expr, policy: ReadDuringWrite, enable: Expr)(
      using cd: ClockDomain): Nothing =
    enableOnAsync(Source.capture)

  /** One-cycle registered read; the result is valid on the next active edge. */
  /** Omission is accepted syntactically solely to issue
    * MEM_RDW_POLICY_REQUIRED during elaboration. */
  def readSync(address: DslSignal)(using cd: ClockDomain): Nothing =
    policyRequired()
  def readSync(address: Expr)(using cd: ClockDomain): Nothing =
    policyRequired()

  inline def readSync(address: DslSignal, policy: ReadDuringWrite)(
      using cd: ClockDomain): DslSignal =
    readSyncExpr(address.ref, policy, None, Source.capture)

  inline def readSync(address: Expr, policy: ReadDuringWrite)(
      using cd: ClockDomain): DslSignal =
    readSyncExpr(address, policy, None, Source.capture)

  /** R15.6 — the three-argument form, which is the read side of `write`'s: the
    * enable stays on the memory port, so the emitted Verilog is the inference
    * template `if (enable) q <= mem[address]` and the result register holds
    * its value on every other edge. */
  inline def readSync(address: DslSignal, policy: ReadDuringWrite, enable: DslSignal)(
      using cd: ClockDomain): DslSignal =
    readSyncExpr(address.ref, policy, Some(enable.ref), Source.capture)

  inline def readSync(address: Expr, policy: ReadDuringWrite, enable: Expr)(
      using cd: ClockDomain): DslSignal =
    readSyncExpr(address, policy, Some(enable), Source.capture)

  inline def readSync(address: DslSignal, policy: ReadDuringWrite, enable: Expr)(
      using cd: ClockDomain): DslSignal =
    readSyncExpr(address.ref, policy, Some(enable), Source.capture)

  inline def readSync(address: Expr, policy: ReadDuringWrite, enable: DslSignal)(
      using cd: ClockDomain): DslSignal =
    readSyncExpr(address, policy, Some(enable.ref), Source.capture)

  /** Synchronous writes in the active clock domain. The three-argument form
    * retains an explicit enable on the memory port so emitted Verilog follows
    * the standard inference template `if (enable) mem[address] <= data`.
    *
    * `inline` for R19's sake, exactly as `:=` is: the overloads exist to spare
    * the caller a `.ref`, and `Loc.here` has to expand at the caller to name
    * the line a bad address or data expression was written on. */
  inline def write(address: DslSignal, data: DslSignal)(using cd: ClockDomain): Unit =
    writeExpr(address.ref, data.ref, None, Loc.here)

  inline def write(address: Expr, data: Expr)(using cd: ClockDomain): Unit =
    writeExpr(address, data, None, Loc.here)

  inline def write(address: DslSignal, data: Expr)(using cd: ClockDomain): Unit =
    writeExpr(address.ref, data, None, Loc.here)

  inline def write(address: Expr, data: DslSignal)(using cd: ClockDomain): Unit =
    writeExpr(address, data.ref, None, Loc.here)

  inline def write(address: DslSignal, data: Bundle)(using cd: ClockDomain): Unit =
    writeExpr(address.ref, data.whole.ref, None, Loc.here)

  inline def write(address: DslSignal, data: DslSignal, enable: DslSignal)(using cd: ClockDomain): Unit =
    writeExpr(address.ref, data.ref, Some(enable.ref), Loc.here)

  inline def write(address: DslSignal, data: Bundle, enable: DslSignal)(using cd: ClockDomain): Unit =
    writeExpr(address.ref, data.whole.ref, Some(enable.ref), Loc.here)

  inline def write(address: Expr, data: Expr, enable: Expr)(using cd: ClockDomain): Unit =
    writeExpr(address, data, Some(enable), Loc.here)

  private def readAsyncExpr(address: Expr, policy: ReadDuringWrite, src: Source)(
      using cd: ClockDomain): DslSignal =
    if kind == scalahdl.core.MemoryKind.BlockRam then
      throw MemoryElaborationError(
        "MEM_ASYNC_READ_ON_BRAM",
        "BlockRam does not support readAsync; use readSync for 1-cycle latency, or choose LutRam/Registers for a 0-cycle read",
        src.loc)
    addRead(address, policy, synchronous = false, None, src)

  private def readSyncExpr(address: Expr, policy: ReadDuringWrite,
                           enable: Option[Expr], src: Source)(
      using cd: ClockDomain): DslSignal =
    addRead(address, policy, synchronous = true, enable, src)

  private def enableOnAsync(src: Source): Nothing =
    throw MemoryElaborationError(
      "MEM_READ_ENABLE_ON_ASYNC",
      "an asynchronous read is a continuous array read and has no register to " +
      "hold a value over, so it takes no enable; use readSync with the " +
      "condition as its enable, or gate whatever consumes the result",
      src.loc)

  /** R15.6 — a read takes the condition it is read under.
    *
    * The read side of R15.5, and the same argument: a read port is an address
    * and a result register, so the `when`/`switch` nest around the call had
    * nowhere to go and was **dropped** — the read compiled, elaborated,
    * validated and updated its register on every clock. An explicit `enable`
    * is ANDed on the end, outermost scope term first.
    *
    * An *asynchronous* read is refused rather than given a term, because there
    * is nothing for one to gate: the result is a wire that continuously reads
    * the array, and a condition around it cannot change what the wire carries.
    * That is `MEM_READ_ENABLE_ON_ASYNC` whether the condition arrived as an
    * argument or as a surrounding scope. */
  private def addRead(address: Expr, policy: ReadDuringWrite,
                      synchronous: Boolean, enable: Option[Expr],
                      src: Source)(using cd: ClockDomain): DslSignal =
    val resultName = src.name.getOrElse(
      throw MemoryElaborationError("MEM_READ_RESULT_ANONYMOUS",
        "memory read result must be bound to a val", src.loc))
    val domain = module.builder.activeDomain(cd)
    val scoped = module.builder.scopeCondition("memory read", src.loc,
      scalahdl.diag.Code.MEM_READ_IN_DEFAULT_ARM)
    if !synchronous && scoped.isDefined then enableOnAsync(src)
    val guard = (scoped, enable) match
      case (None, e)          => e
      case (Some(c), None)    => Some(c)
      case (Some(c), Some(e)) => Some(Expr.BinOp("&", c, e))
    val result = module.builder.addMemoryReadResult(resultName, elemType,
      synchronous, domain, src.loc)
    ir.addRead(result.id, address, synchronous, policy, domain, src.loc, guard)
    result

  private def policyRequired(): Nothing =
    throw MemoryElaborationError(
      "MEM_RDW_POLICY_REQUIRED",
      "memory read port requires an explicit ReadFirst, WriteFirst, WriteFirstPort " +
      "or Undefined policy",
      ir.loc)

  /** R15.5 — a write takes the condition it is written under.
    *
    * `when(c) { mem.write(a, d) }` is `if (c) mem[a] <= d;`, which is what the
    * surrounding Verilog writes and what a reader expects. A write port has no
    * statement position to carry a nest in — it is address, data and an enable
    * — so the nest is folded into the enable here, outermost term first, and
    * an explicit `enable` argument is ANDed on the end. A write at the top
    * level of a module gets no term and is unconditional, exactly as before. */
  private def writeExpr(address: Expr, data: Expr, enable: Option[Expr], loc: Loc)
                       (using cd: ClockDomain): Unit =
    module.builder.checkNotInSimOnly(s"memory `${ir.name}` is written",
      "a `simOnly` block reads and reports; move the write outside it", loc)
    val domain = module.builder.activeDomain(cd)
    val scoped = module.builder.scopeCondition("memory write", loc)
    val guard = (scoped, enable) match
      case (None, e)             => e
      case (Some(c), None)       => Some(c)
      case (Some(c), Some(e))    => Some(Expr.BinOp("&", c, e))
    ir.addWrite(address, data, guard, domain, loc)

/** Explicit constructors. There is intentionally no generic/default Memory
  * constructor: the user must select Registers, LutRam, or BlockRam. The
  * three-argument forms add R15.4 initial contents; every kind accepts them,
  * because the image is loaded into the declared array and is therefore
  * independent of which physical family the array infers to. */
inline def Registers(depth: Int, elemType: Type)(using m: Module): MemoryHandle =
  memory(scalaHDLKind = scalahdl.core.MemoryKind.Registers, depth, elemType, None, Source.capture)

inline def Registers(depth: Int, elemType: Bundle)(using m: Module): MemoryHandle =
  memory(scalaHDLKind = scalahdl.core.MemoryKind.Registers, depth, elemType.toType, None, Source.capture)

inline def Registers(depth: Int, elemType: Type, init: MemoryInit)(using m: Module): MemoryHandle =
  memory(scalaHDLKind = scalahdl.core.MemoryKind.Registers, depth, elemType, Some(init), Source.capture)

inline def Registers(depth: Int, elemType: Bundle, init: MemoryInit)(using m: Module): MemoryHandle =
  memory(scalaHDLKind = scalahdl.core.MemoryKind.Registers, depth, elemType.toType, Some(init), Source.capture)

inline def LutRam(depth: Int, elemType: Type)(using m: Module): MemoryHandle =
  memory(scalaHDLKind = scalahdl.core.MemoryKind.LutRam, depth, elemType, None, Source.capture)

inline def LutRam(depth: Int, elemType: Bundle)(using m: Module): MemoryHandle =
  memory(scalaHDLKind = scalahdl.core.MemoryKind.LutRam, depth, elemType.toType, None, Source.capture)

inline def LutRam(depth: Int, elemType: Type, init: MemoryInit)(using m: Module): MemoryHandle =
  memory(scalaHDLKind = scalahdl.core.MemoryKind.LutRam, depth, elemType, Some(init), Source.capture)

inline def LutRam(depth: Int, elemType: Bundle, init: MemoryInit)(using m: Module): MemoryHandle =
  memory(scalaHDLKind = scalahdl.core.MemoryKind.LutRam, depth, elemType.toType, Some(init), Source.capture)

inline def BlockRam(depth: Int, elemType: Type)(using m: Module): MemoryHandle =
  memory(scalaHDLKind = scalahdl.core.MemoryKind.BlockRam, depth, elemType, None, Source.capture)

inline def BlockRam(depth: Int, elemType: Bundle)(using m: Module): MemoryHandle =
  memory(scalaHDLKind = scalahdl.core.MemoryKind.BlockRam, depth, elemType.toType, None, Source.capture)

inline def BlockRam(depth: Int, elemType: Type, init: MemoryInit)(using m: Module): MemoryHandle =
  memory(scalaHDLKind = scalahdl.core.MemoryKind.BlockRam, depth, elemType, Some(init), Source.capture)

inline def BlockRam(depth: Int, elemType: Bundle, init: MemoryInit)(using m: Module): MemoryHandle =
  memory(scalaHDLKind = scalahdl.core.MemoryKind.BlockRam, depth, elemType.toType, Some(init), Source.capture)

private def memory(scalaHDLKind: scalahdl.core.MemoryKind, depth: Int,
                   elemType: Type, init: Option[scalahdl.core.MemoryInit],
                   src: Source)(using m: Module): MemoryHandle =
  init.foreach(checkInit(_, src))
  val ir = m.builder.addMemory(src, scalaHDLKind, depth, elemType, init)
  new MemoryHandle(ir, scalaHDLKind, depth, elemType, init, m)

/** R15.4 — the path travels verbatim into a Verilog string literal, so it must
  * be something a string literal can carry. Blank and control characters are
  * rejected at the declaration rather than silently producing a load the
  * simulator cannot resolve. */
private def checkInit(init: scalahdl.core.MemoryInit, src: Source): Unit =
  val path = init match
    case scalahdl.core.MemoryInit.HexFile(p) => p
  if path.isEmpty || path.forall(_.isWhitespace) then
    throw MemoryElaborationError(
      "MEM_INIT_PATH_EMPTY",
      "memory initial-contents path is blank; name the image file the simulator should read",
      src.loc)
  if path.exists(c => c.isControl) then
    throw MemoryElaborationError(
      "MEM_INIT_PATH_UNPRINTABLE",
      s"memory initial-contents path contains a control character: ${path.map(c => if c.isControl then '?' else c)}",
      src.loc)
