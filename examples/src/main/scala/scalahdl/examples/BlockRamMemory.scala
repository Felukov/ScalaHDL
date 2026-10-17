package scalahdl.examples

import scalahdl.dsl.{*, given}
import scalahdl.elab.Module
import scalahdl.clock.ResetKind

/** A block RAM demonstrating its required one-cycle synchronous read.
  *
  * ReadFirst selects the portable inference pattern that returns the old word
  * when a read and write target the same address on an edge. */
class BlockRamMemory extends Module:
  val clk       = In(Clock())
  val readAddr  = In(UInt(10))
  val writeAddr = In(UInt(10))
  val writeData = In(UInt(8))
  val readData  = Out(UInt(8))

  given defaultDomain: ClockDomain = ClockDomain(clk, ResetKind.None)

  val memory = BlockRam(1024, UInt(8))
  val word = memory.readSync(readAddr, ReadFirst)
  memory.write(writeAddr, writeData)
  readData := word
