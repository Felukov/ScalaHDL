package scalahdl.golden

import java.nio.file.Paths

import scalahdl.dsl.{*, given}
import scalahdl.elab.{BlackBox, Module}
import scalahdl.clock.ResetKind
import scalahdl.core.{MemoryKind, ReadDuringWrite, ResetPolarity}
import scalahdl.core.ResetPolarity.{ActiveHigh, ActiveLow}
import scalahdl.emit.{AlteraFamily, Emission, Vendor}

/** The `core` module's golden corpus.
  *
  * Every construction the `core` tests can exercise has a golden here, held
  * byte-for-byte by [[GoldenCorpus]]. The registry maps each golden's name to
  * the module that produces it; the runner elaborates, emits and compares.
  *
  * The designs the *emission* is about live in [[CoreGoldens]], one class per
  * construction, so a reader can see which design pins which shape. The
  * assertions that a golden cannot express — the count of `$signed` wrappers,
  * a diagnostic's text — stay as separate tests below. */
class CoreGoldenSuite extends GoldenCorpus:
  override protected def corpusRoot: java.nio.file.Path =
    Paths.get("core/src/test/resources/scalahdl/golden")

  registerCorpus(CoreGoldens.entries)

  // ---- assertions a golden cannot express --------------------------------

  /** R21.6 — the golden pins the exact text; this pins the *number* of wrappers,
    * which fails on a different change: adding a port rewrites the golden
    * wholesale, and this still catches a wrapper dropped in the same commit. */
  test("$signed appears once per signed operand and nowhere else (R21.6)"):
    val v = CoreGoldens.emitSignedSurface()
    assertEquals(v.split("\\$signed").length - 1, 12, s"one wrapper per signed operand: $v")
    assert(!v.contains("signed ["), s"R21.6: signedness is never declared: $v")

  /** R39 — a report states its content, not its bytes. The `.cdc.rpt` of the
    * [[CoreGoldens.CdcCrossings]] design names each crossing and the primitive
    * that blesses it; the model-derived numbers and the source locations it also
    * carries are deliberately not asserted, so they may move. */
  test("the CDC report names each crossing and its primitive (R39)"):
    val design = (new CoreGoldens.CdcCrossings).elaborateDesign()
    val report = new Emission(version).emit(design, "CdcCrossings").reports
      .collectFirst { case (name, body) if name == "CdcCrossings.cdc.rpt" => body }
      .getOrElse(fail("no CDC report"))
    assert(report.contains("CDC report: CdcCrossings"), report)
    assert(report.contains("Safe"), report)
    assert(report.contains("Unsafe\n(none)"), report)
    // The two synchronised registers and the dual-domain memory's word read.
    assert(report.contains("enableWrite: writeClk -> readClk via SyncChain(2)"), report)
    assert(report.contains("pointerWrite: writeClk -> readClk via SyncChain(2)"), report)
    assert(report.contains("word: readClk -> writeClk via DualDomainMemory(memory)"), report)

  /** R15.7 — a memory carries a hint only in the words of a named vendor, and
    * nothing else about the declaration moves. The Xilinx and Altera forms are
    * the `mem/` goldens; this pins the absent-hint case, which no golden holds
    * because a golden names exactly one vendor. */
  test("a memory hint is written only for a named vendor (R15.7)"):
    val plain = CoreGoldens.emitMemoryFor(None)
    assert(plain.contains("  reg [7:0] memory [0:255];"), plain)
    assert(!plain.contains("(*"), plain)
    val xilinx = CoreGoldens.emitMemoryFor(Some(Vendor.Xilinx))
    assertEquals(xilinx.replace("(* ram_style = \"registers\" *) ", ""), plain)
    val altera = CoreGoldens.emitMemoryFor(Some(Vendor.Altera(AlteraFamily.CycloneV)))
    assertEquals(altera.replace("(* ramstyle = \"logic\" *) ", ""), plain)

/** Every `core` golden: the module that produces it and the two emission
  * choices the file holds. Named `Golden`s are combined here so the corpus has
  * one registry — the orphan check needs to see the whole set at once. */
object CoreGoldens:

  // ---- R15.2/R15.1/R15.6 — the memory surface, migrated from MemoryGoldenSuite

  /** R15.2 — one kind, one read-during-write policy, one synchronous read and
    * one write. */
  final class Pattern(kind: MemoryKind, policy: ReadDuringWrite) extends Module:
    override def moduleName: String = s"Memory_${kind}_${policy}"
    val clk = In(Clock())
    val rst = In(Reset(ResetPolarity.ActiveHigh))
    given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ResetPolarity.ActiveHigh)
    val raddr = In(UInt(8))
    val waddr = In(UInt(8))
    val data = In(UInt(16))
    val mem = kind match
      case MemoryKind.Registers => Registers(256, UInt(16))
      case MemoryKind.LutRam    => LutRam(256, UInt(16))
      case MemoryKind.BlockRam  => BlockRam(256, UInt(16))
    val q = mem.readSync(raddr, policy)
    mem.write(waddr, data)

  /** R15.3/R05.5 — a mixed-policy dual-port block RAM: a `ReadFirst` in one
    * domain and a `WriteFirst` in another, on one array. */
  final class MixedDualPort extends Module:
    val clkA = In(Clock())
    val clkB = In(Clock())
    given defaultDomain: ClockDomain = ClockDomain(clkA, ResetKind.None)
    val domainB = ClockDomain(clkB, ResetKind.None)
    val addrA = In(UInt(8))
    val addrB = In(UInt(8))
    val dataB = In(UInt(16))
    val mem = BlockRam(256, UInt(16))
    val observed = mem.readSync(addrA, ReadFirst)
    withClock(domainB):
      val updated = mem.readSync(addrB, WriteFirst)
      mem.write(addrB, dataB)

  /** R15.6 — two synchronous read ports whose enables are a condition and its
    * negation, and two write ports in one clock. */
  final class ReadEnabled extends Module:
    override def moduleName: String = "Memory_BlockRam_ReadEnabled"
    val clk = In(Clock())
    given defaultDomain: ClockDomain = ClockDomain(clk, ResetKind.None)
    val ready = In(Bool())
    val fill = In(Bool())
    val rd = In(UInt(8))
    val wr = In(UInt(8))
    val data = In(UInt(16))
    val mem = BlockRam(256, UInt(16))
    val toDrive = mem.readSync(rd, ReadFirst, ready)
    val toHost = mem.readSync(wr, ReadFirst, !ready)
    mem.write(wr, data, fill)
    when(ready) { mem.write(rd, data) }

  /** R15.1 — a true dual port: two addresses, each read and written. */
  final class TrueDualPort extends Module:
    override def moduleName: String = "Memory_BlockRam_TrueDualPort"
    val clk = In(Clock())
    given defaultDomain: ClockDomain = ClockDomain(clk, ResetKind.None)
    val addrA = In(UInt(8))
    val dataA = In(UInt(16))
    val writeA = In(Bool())
    val addrB = In(UInt(8))
    val dataB = In(UInt(16))
    val writeB = In(Bool())
    val mem = BlockRam(256, UInt(16))
    val qA = mem.readSync(addrA, WriteFirstPort)
    mem.write(addrA, dataA, writeA)
    val qB = mem.readSync(addrB, WriteFirstPort)
    mem.write(addrB, dataB, writeB)

  /** R15.1 — a register file written from more than one place, which stays in
    * one block. */
  final class ManyWrittenRegisters extends Module:
    override def moduleName: String = "Memory_Registers_ManyWritten"
    val clk = In(Clock())
    given defaultDomain: ClockDomain = ClockDomain(clk, ResetKind.None)
    val addr = In(UInt(8))
    val other = In(UInt(8))
    val data = In(UInt(8))
    val first = In(Bool())
    val second = In(Bool())
    val mem = Registers(256, UInt(8))
    val q = mem.readAsync(addr, ReadFirst)
    mem.write(addr, data, first)
    mem.write(other, data, second)

  /** R15.4 — a byte lane with an image at time zero, one synchronous read, and
    * a strobe-enabled write. */
  final class Initialized(kind: MemoryKind) extends Module:
    override def moduleName: String = s"Memory_${kind}_Initialized"
    val clk = In(Clock())
    given defaultDomain: ClockDomain = ClockDomain(clk, ResetKind.None)
    val addr = In(UInt(8))
    val data = In(UInt(8))
    val enable = In(Bool())
    val mem = kind match
      case MemoryKind.Registers => Registers(256, UInt(8), HexFile("image.hex"))
      case MemoryKind.LutRam    => LutRam(256, UInt(8), HexFile("image.hex"))
      case MemoryKind.BlockRam  => BlockRam(256, UInt(8), HexFile("image.hex"))
    val q = mem.readSync(addr, ReadFirst)
    mem.write(addr, data, enable)

  // ---- R05 — the CDC crossing group, migrated from the SafeClockCrossing example

  /** R05.1/R05.3/R05.4 — the three crossing primitives in one design: a
    * single-bit level through a two-flop [[SyncChain]], a Gray-code pointer
    * through another, and a block RAM whose read port lives in the second
    * domain. */
  final class CdcCrossings extends Module:
    val writeClk = In(Clock())
    val readClk = In(Clock())
    val writeEnable = In(Bool())
    val writePointer = In(GrayCode(4))
    val writeAddress = In(UInt(3))
    val writeData = In(UInt(8))
    val readAddress = In(UInt(3))
    val enableRead = Out(Bool())
    val pointerRead = Out(GrayCode(4))
    val readData = Out(UInt(8))

    given defaultDomain: ClockDomain = ClockDomain(writeClk, ResetKind.None)
    val readDomain = ClockDomain(readClk, ResetKind.None)

    val enableWrite = Reg(Bool())
    val pointerWrite = Reg(GrayCode(4))
    enableWrite := writeEnable
    pointerWrite := writePointer

    val memory = BlockRam(8, UInt(8))
    memory.write(writeAddress, writeData)

    withClock(readDomain):
      val level = SyncChain(enableWrite)
      val pointer = SyncChain(pointerWrite)
      val word = memory.readSync(readAddress, ReadFirst)
      enableRead := level
      pointerRead := pointer
      readData := word

  // ---- R07/R23 — a bundle carried whole across an instance boundary

  /** R07 — a direction-free bus blueprint, used at both ends of one level. */
  final case class Bus(addrWidth: Int, dataWidth: Int) extends Bundle:
    val we = Bool()
    val addr = UInt(addrWidth)
    val data = UInt(dataWidth)

  /** The answer half, declared by the module that produces it. */
  final case class Reply(dataWidth: Int) extends Bundle:
    val ack = Bool()
    val data = UInt(dataWidth)

  /** A child that receives a request bundle and returns a reply bundle. */
  final class BusChild extends Module:
    given defaultDomain: ClockDomain = scalahdl.clock.ClockDomain.noClock
    val req = In(Bus(2, 8))
    val resp = Out(Reply(8))
    resp.ack := req.we
    resp.data := req.data

  /** R07/R23 — the parent hands the request down whole and returns the child's
    * reply whole; the boundary flattens each bundle to its leaves (R07.1). */
  final class BundleBoundary extends Module:
    given defaultDomain: ClockDomain = scalahdl.clock.ClockDomain.noClock
    val req = In(Bus(2, 8))
    val resp = Out(Reply(8))
    val u = inst(new BusChild)
    u.req := req
    resp := u.resp

  // ---- R16/R14 — an instance array whose ports are a Vec of bundles

  /** R16/R14 — three children of one module, each fed its own element of a `Vec`
    * of request bundles; the responses come back the same way. */
  final class InstanceArrayBundles extends Module:
    given defaultDomain: ClockDomain = scalahdl.clock.ClockDomain.noClock
    val req = In(Bus(2, 8))
    val resp = Out(Reply(8))
    val requests = Wire(Vec(3, Bus(2, 8)))
    val responses = Wire(Vec(3, Reply(8)))
    val slot = instances(3)(_ => new BusChild)
    for i <- 0 until 3 do
      slot(i).req := requests(i)
      responses(i) := slot(i).resp
      requests(i).we := False
      requests(i).addr := req.addr
      requests(i).data := req.data
    resp.ack := responses(0).ack
    resp.data := responses(0).data

  // ---- R14/R14.3 — a Vec-of-bundle port read through a decode-driven Mux

  /** R14 — a two-field entry bundle, for the decoded read. */
  final case class Entry(w: Int) extends Bundle:
    val addr = UInt(w)
    val prot = Bits(3)

  /** R14/R14.3 — a `Vec` of bundles at a port, an internal `Reg` `Vec` written
    * at a runtime index (R14.2), and a one-hot `decode` driving a `Mux` read. */
  final class DecodedVecPort extends Module:
    val clk = In(Clock())
    given defaultDomain: ClockDomain = ClockDomain(clk, ResetKind.None)
    val req = In(Entry(8))
    val waddr = In(UInt(2))
    val rd = Out(Vec(4, Entry(8)))
    val entries = Reg(Vec(4, Entry(8)), init = null)
    entries(waddr).addr := req.addr
    entries(waddr).prot := req.prot
    val sel = decode(waddr, 4)
    for j <- 0 until 4 do
      rd(j).addr := Mux(sel(j), entries(j).addr, 0.U(8))
      rd(j).prot := Mux(sel(j), entries(j).prot, 0.U(3).asBits)

  private def memoryEntries: List[Golden] =
    val singles = for
      kind <- MemoryKind.values
      policy <- ReadDuringWrite.values
    yield Golden(s"mem/${kind}_${policy}_single",
      () => new Pattern(kind, policy), Some(s"Memory_${kind}_${policy}"),
      Some(Vendor.Xilinx))
    val named = List(
      Golden("mem/BlockRam_mixed_dual", () => new MixedDualPort,
        Some("MixedDualPort"), Some(Vendor.Xilinx)),
      Golden("mem/BlockRam_read_enable", () => new ReadEnabled,
        Some("Memory_BlockRam_ReadEnabled"), Some(Vendor.Xilinx)),
      Golden("mem/BlockRam_true_dual", () => new TrueDualPort,
        Some("Memory_BlockRam_TrueDualPort"), Some(Vendor.Xilinx)),
      Golden("mem/Registers_many_written", () => new ManyWrittenRegisters,
        Some("Memory_Registers_ManyWritten"), Some(Vendor.Xilinx)))
    val inits = MemoryKind.values.toList.map(kind =>
      Golden(s"mem/${kind}_init", () => new Initialized(kind),
        Some(s"Memory_${kind}_Initialized"), Some(Vendor.Xilinx)))
    // R15.7 — the same three kinds in Altera's words, which the `core` memory
    // goldens above do not pin (they name `Vendor.Xilinx` only).
    val altera = MemoryKind.values.toList.map(kind =>
      Golden(s"mem/Altera_${kind}_init", () => new Initialized(kind),
        Some(s"Memory_${kind}_Initialized"),
        Some(Vendor.Altera(AlteraFamily.CycloneV))))
    singles.toList ++ named ++ inits ++ altera

  // ---- R21.6 — the signed emission surface, migrated from SignedGoldenSuite

  /** Every operation whose emission depends on operand signedness, beside the
    * unsigned form of the same operation. */
  final class SignedSurface extends Module:
    val clk = In(Clock())
    val rst = In(Reset(ActiveHigh))
    given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)
    val s = In(SInt(8))
    val t = In(SInt(8))
    val u = In(UInt(8))
    val v = In(UInt(8))
    val n = In(UInt(3))
    val sra = Out(SInt(8)); sra := s >>> n
    val srl = Out(SInt(8)); srl := s >> n
    val usrl = Out(UInt(8)); usrl := u >> n
    val slt = Out(Bool()); slt := s < t
    val sle = Out(Bool()); sle := s <= t
    val sgt = Out(Bool()); sgt := s > t
    val sge = Out(Bool()); sge := s >= t
    val ult = Out(Bool()); ult := u < v
    val sprod = Out(SInt(16)); sprod := s * t
    val uprod = Out(UInt(16)); uprod := u * v
    val swide = Out(SInt(16)); swide := s.resize(16)
    val uwide = Out(UInt(16)); uwide := u.resize(16)
    val dec = Out(SInt(8)); dec := s + (-1).S(8)

  def emitSignedSurface(): String =
    val design = (new SignedSurface).elaborateDesign()
    new scalahdl.emit.VerilogEmitter("0.1.2").emitModule(design.top, "SignedSurface")

  /** A single register-backed memory, for the absent-hint assertion. The value
    * is named `memory` so the declaration line under test is the one the test
    * names. */
  final class HintedMemory extends Module:
    val clk = In(Clock())
    given defaultDomain: ClockDomain = ClockDomain(clk, ResetKind.None)
    val addr = In(UInt(8))
    val data = In(UInt(8))
    val memory = Registers(256, UInt(8))
    val q = memory.readAsync(addr, ReadFirst)
    memory.write(addr, data)

  /** Emit the one memory with `vendor`'s hint, or none when it is `None`. */
  def emitMemoryFor(vendor: Option[Vendor]): String =
    val design = (new HintedMemory).elaborateDesign()
    new scalahdl.emit.VerilogEmitter("0.1.2", vendor)
      .emitModule(design.top, "HintedMemory")

  // ---- the constructions without golden coverage ----------------

  /** V5 — an internal aggregate declaration expands to scalar leaves, and every
    * `Vec` dimension is preserved as an unpacked array dimension (R14.1). */
  final class AggregateDeclaration extends Module:
    val clk = In(Clock())
    given defaultDomain: ClockDomain = ClockDomain(clk, ResetKind.None)
    val d = In(UInt(8))
    val entries = Reg(Vec(4, UInt(8)))
    val nested = Wire(Vec(2, Vec(3, UInt(4))))
    entries(0) := d
    entries(1) := d
    entries(2) := d
    entries(3) := d
    for i <- 0 until 2; j <- 0 until 3 do nested(i)(j) := d(3, 0)

  /** V6 — a power-up value is written on the declaration (`= value`) in any
    * domain, and the declaration is the only place it appears (R13.8). */
  final class PowerUpDeclaration extends Module:
    val clk = In(Clock())
    given defaultDomain: ClockDomain = ClockDomain(clk, ResetKind.None)
    val armed = Reg(Bool(), powerUp = True)
    val count = Reg(UInt(8), powerUp = 5)
    armed := !armed
    count := count + 1

  /** V18 — an asynchronous reset puts its edge in the sensitivity list and its
    * polarity in the branch (R13/R05). */
  final class AsyncReset extends Module:
    val clk = In(Clock())
    val rst_n = In(Reset(ActiveLow))
    given defaultDomain: ClockDomain = ClockDomain(clk, rst_n, ResetKind.Async, ActiveLow)
    val q = Reg(UInt(8), init = 0.U(8))
    q := q + 1.U(8)

  /** V21 — a `parallelCase` carries R26's decided claim into the Verilog as the
    * `// synopsys parallel_case` attribute (R26.1). */
  final class ParallelCaseAttribute extends Module:
    given defaultDomain: ClockDomain = scalahdl.clock.ClockDomain.noClock
    val opcode = In(UInt(8))
    val shape = Out(UInt(4))
    shape := 0.U(4)
    parallelCase {
      on((opcode & 0xF0.U(8)) === 0x70.U(8)) { shape := 1.U(4) }
      on((opcode & 0xF0.U(8)) === 0x40.U(8)) { shape := 2.U(4) }
      default { }
    }

  /** V23 — a `simOnly` block: the `translate_off`/`translate_on` guard, the
    * `$display`, the `$stop`, and the domain's reset guard (R27). */
  final class CheckBlockModule extends Module:
    val clk = In(Clock())
    val rst = In(Reset(ActiveHigh))
    given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)
    val d = In(Bool())
    val q = Out(Bool())
    q := d
    simOnly { check(q === d, "the output is not the input") }

  /** A child with no ports, for V24. */
  final class Empty extends Module:
    given defaultDomain: ClockDomain = scalahdl.clock.ClockDomain.noClock

  /** V24 — a port-less instantiation still needs its parentheses: `Empty u();`
    * rather than `Empty u;`, which parses as a variable declaration (R09/R23). */
  final class PortlessInstance extends Module:
    given defaultDomain: ClockDomain = scalahdl.clock.ClockDomain.noClock
    val u = inst(new Empty)

  /** An external module carrying two literal parameters, for V25. */
  final class ParamBox extends BlackBox:
    override val moduleName = "vendor_param"
    val clk = In(Clock()).named("clk")
    val d = In(UInt(8)).named("d")
    val q = Out(UInt(8)).named("q")
    param("WIDTH", 8)
    param("LABEL", "lane")
    outputDomain(q, enclosingDomain)

  /** V25 — a blackbox's `#(.P(v))` parameter list stays ordered and typed until
    * this rendering boundary (R18.3). */
  final class BlackboxParameters extends Module:
    val clk = In(Clock())
    given defaultDomain: ClockDomain = ClockDomain(clk, ResetKind.None)
    val d = In(UInt(8))
    val q = Out(UInt(8))
    val u = inst(new ParamBox)
    u.clk := clk
    u.d := d
    q := u.q


  /** A generated child with two inputs and an output, for V26. */
  final class Adder extends Module:
    given defaultDomain: ClockDomain = scalahdl.clock.ClockDomain.noClock
    val a = In(UInt(8))
    val b = In(UInt(8))
    val y = Out(UInt(8))
    y := a + b

  /** V26 — an instance's ports connect by name (R23). */
  final class NamedConnections extends Module:
    given defaultDomain: ClockDomain = scalahdl.clock.ClockDomain.noClock
    val x = In(UInt(8))
    val z = Out(UInt(8))
    val u = inst(new Adder)
    u.a := x
    u.b := 0.U(8)
    z := u.y

  /** V31 — a `Mux` emits one ternary and is never collapsed (R12/R2). */
  final class TernaryMux extends Module:
    given defaultDomain: ClockDomain = scalahdl.clock.ClockDomain.noClock
    val sel = In(Bool())
    val a = In(UInt(8))
    val b = In(UInt(8))
    val q = Out(UInt(8))
    q := Mux(sel, a, b)

  /** V32 — a constant part-select and a bit-select carry their window (R6.2). */
  final class ConstantSelect extends Module:
    given defaultDomain: ClockDomain = scalahdl.clock.ClockDomain.noClock
    val w = In(UInt(8))
    val hi = Out(UInt(4))
    val lo = Out(Bool())
    hi := w(7, 4)
    lo := w(0)

  /** V34 — `Cat` emits `{a, b}` and `Repeat` emits `{n{a}}` (R21.5). */
  final class ConcatRepeat extends Module:
    given defaultDomain: ClockDomain = scalahdl.clock.ClockDomain.noClock
    val a = In(UInt(4))
    val b = In(UInt(4))
    val cat = Out(UInt(8))
    val rep = Out(UInt(8))
    cat := Cat(a, b)
    rep := Fill(2, a)

  /** V37 — an `unsafeCrossDomain` hatch emits the `/* ASYNC_REG: … */` comment
    * and its written justification beside the read (R05.2). */
  final class AsyncRegComment extends Module:
    val clk = In(Clock())
    given defaultDomain: ClockDomain = ClockDomain(clk, ResetKind.None)
    val flag = Reg(Bool(), powerUp = False)
    flag := !flag
    val seen = Out(Bool())
    seen := unsafeCrossDomain(flag, "quasi-static after enable")

  /** R34 — a runtime selection on a packed vector: a runtime bit-select emits
    * `word[idx]` and a runtime window `word[idx +: w]`, with no generated mux. */
  final class RuntimeSelect extends Module:
    val clk = In(Clock())
    val rst = In(Reset(ActiveHigh))
    given defaultDomain: ClockDomain = ClockDomain(clk, rst, ResetKind.Sync, ActiveHigh)
    val word = In(UInt(32))
    val idx = In(UInt(5))
    val lane = In(UInt(8))
    val bito = Out(Bool())
    val sel = Out(UInt(8))
    val acc = Reg(UInt(32), init = 0.U(32))
    bito := word(idx)
    sel := word(idx, 8)
    acc(idx, 8) := lane

  /** R12/R12.2 — a `when`/`elsewhen`/`otherwise` chain emits one `else` per
    * link, every branch a block (`else if` flattens the chain). */
  final class WhenChain extends Module:
    given defaultDomain: ClockDomain = scalahdl.clock.ClockDomain.noClock
    val sel = In(Bool())
    val sel2 = In(Bool())
    val a = In(UInt(8))
    val b = In(UInt(8))
    val y = Out(UInt(8))
    when(sel) { y := a }
    .elsewhen(sel2) { y := b }
    .otherwise { y := 0.U(8) }

  /** R22 — a `switch` emits one native `case`: one item per alternative in
    * elaboration order, aliases as comma-separated labels, `default` only where
    * the source had one. */
  final class SwitchCase extends Module:
    given defaultDomain: ClockDomain = scalahdl.clock.ClockDomain.noClock
    val opcode = In(UInt(3))
    val result = Out(UInt(8))
    val illegal = Out(Bool())
    result := 0.U(8)
    illegal := 0.U(1)
    switch(opcode) {
      is(0.U(3)) { result := 11.U(8) }
      is(5.U(3), 6.U(3)) { result := 99.U(8) }
      default { illegal := 1.U(1) }
    }

  /** A two-field bundle, for V38. */
  final class Word extends Bundle:
    val lo = UInt(8)
    val hi = UInt(8)

  /** V38 — a whole-aggregate assignment expands to one statement per leaf,
    * both sides walking the same leaf paths (R07.1/R14). */
  final class WholeAggregate extends Module:
    given defaultDomain: ClockDomain = scalahdl.clock.ClockDomain.noClock
    val in = In(new Word)
    val out = Out(new Word)
    out := in

  def entries: List[Golden] =
    memoryEntries ++ List(
      Golden("SignedSurface", () => new SignedSurface, Some("SignedSurface")),
      Golden("AggregateDeclaration", () => new AggregateDeclaration,
        Some("AggregateDeclaration")),
      Golden("PowerUpDeclaration", () => new PowerUpDeclaration,
        Some("PowerUpDeclaration")),
      Golden("AsyncReset", () => new AsyncReset, Some("AsyncReset")),
      Golden("ParallelCaseAttribute", () => new ParallelCaseAttribute,
        Some("ParallelCaseAttribute")),
      Golden("CheckBlockModule", () => new CheckBlockModule,
        Some("CheckBlockModule")),
      Golden("PortlessInstance", () => new PortlessInstance,
        Some("PortlessInstance")),
      Golden("BlackboxParameters", () => new BlackboxParameters,
        Some("BlackboxParameters")),
      Golden("NamedConnections", () => new NamedConnections,
        Some("NamedConnections")),
      Golden("TernaryMux", () => new TernaryMux, Some("TernaryMux")),
      Golden("ConstantSelect", () => new ConstantSelect, Some("ConstantSelect")),
      Golden("ConcatRepeat", () => new ConcatRepeat, Some("ConcatRepeat")),
      Golden("AsyncRegComment", () => new AsyncRegComment, Some("AsyncRegComment")),
      Golden("WholeAggregate", () => new WholeAggregate, Some("WholeAggregate")),
      Golden("WhenChain", () => new WhenChain, Some("WhenChain")),
      Golden("SwitchCase", () => new SwitchCase, Some("SwitchCase")),
      Golden("RuntimeSelect", () => new RuntimeSelect, Some("RuntimeSelect")),
      Golden("CdcCrossings", () => new CdcCrossings, Some("CdcCrossings")),
      Golden("BundleBoundary", () => new BundleBoundary, Some("BundleBoundary")),
      Golden("InstanceArrayBundles", () => new InstanceArrayBundles,
        Some("InstanceArrayBundles")),
      Golden("DecodedVecPort", () => new DecodedVecPort, Some("DecodedVecPort")))
