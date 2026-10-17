package scalahdl.examples

import scalahdl.dsl.{*, given}
import scalahdl.elab.Module
import scalahdl.clock.ResetKind

/** A complete, safe two-clock example.
  *
  * It demonstrates three clock-domain-crossing constructs together:
  *   - a single-bit level crossing through a two-stage SyncChain,
  *   - a multi-bit GrayCode pointer crossing through another SyncChain,
  *   - a block RAM whose write and read ports live in different domains.
  *
  * Every address presented to the dual-domain memory is local to its port's
  * domain. No unsafeCrossDomain hatch is required, so the generated CDC report
  * has only Safe entries. */
class SafeClockCrossing extends Module:
  val writeClk = In(Clock())
  val readClk = In(Clock())
  val writeEnable = In(Bool())
  val writeAddress = In(UInt(4))
  val writePointerGray = In(GrayCode(4))
  val writeData = In(UInt(8))
  val readAddress = In(UInt(4))
  val writeEnableRead = Out(Bool())
  val writePointerRead = Out(GrayCode(4))
  val readData = Out(UInt(8))

  given defaultDomain: ClockDomain = ClockDomain(writeClk, ResetKind.None)
  val readDomain = ClockDomain(readClk, ResetKind.None)

  // Domain-owned source registers make the crossing origin explicit. Input
  // ports themselves are intentionally domain-neutral in the IR.
  val enableWrite = Reg(Bool())
  val pointerWrite = Reg(GrayCode(4))
  enableWrite := writeEnable
  pointerWrite := writePointerGray

  val memory = BlockRam(16, UInt(8))
  memory.write(writeAddress, writeData)

  withClock(readDomain):
    val enableSynced = SyncChain(enableWrite)
    val pointerSynced = SyncChain(pointerWrite)
    val word = memory.readSync(readAddress, ReadFirst)

    writeEnableRead := enableSynced
    writePointerRead := pointerSynced
    readData := word
