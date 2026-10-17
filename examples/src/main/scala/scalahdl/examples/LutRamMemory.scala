package scalahdl.examples

import scalahdl.dsl.{*, given}
import scalahdl.elab.Module
import scalahdl.clock.ResetKind

/** A distributed LUT RAM with a zero-cycle asynchronous read.
  *
  * WriteFirst requests explicit forwarding of writeData when the read and
  * write addresses collide. */
class LutRamMemory extends Module:
  val clk       = In(Clock())
  val readAddr  = In(UInt(6))
  val writeAddr = In(UInt(6))
  val writeData = In(UInt(8))
  val readData  = Out(UInt(8))

  given defaultDomain: ClockDomain = ClockDomain(clk, ResetKind.None)

  val memory = LutRam(64, UInt(8))
  val word = memory.readAsync(readAddr, WriteFirst)
  memory.write(writeAddr, writeData)
  readData := word
