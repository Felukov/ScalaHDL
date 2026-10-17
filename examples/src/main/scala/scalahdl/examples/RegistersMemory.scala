package scalahdl.examples

import scalahdl.dsl.{*, given}
import scalahdl.elab.Module
import scalahdl.clock.ResetKind

/** A small register-backed memory with a zero-cycle asynchronous read.
  *
  * The read-during-write behavior is explicit: ReadFirst returns the old word
  * when the read and write addresses collide on a clock edge. */
class RegistersMemory extends Module:
  val clk       = In(Clock())
  val readAddr  = In(UInt(4))
  val writeAddr = In(UInt(4))
  val writeData = In(UInt(8))
  val readData  = Out(UInt(8))

  given defaultDomain: ClockDomain = ClockDomain(clk, ResetKind.None)

  val memory = Registers(16, UInt(8))
  val word = memory.readAsync(readAddr, ReadFirst)
  memory.write(writeAddr, writeData)
  readData := word
