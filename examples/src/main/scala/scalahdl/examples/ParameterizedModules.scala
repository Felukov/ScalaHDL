package scalahdl.examples

import scalahdl.dsl.{*, given}
import scalahdl.elab.{Module, moduleName, param}
import scalahdl.clock.ClockDomain

/** Module-naming example (R16/R16.1).
  *
  * `width` and `registered` are declared explicitly as constructor parameters
  * for module naming and identity. For example:
  *
  *   - `WordPipe(8, false)`  emits as `WordPipe_w8`
  *   - `WordPipe(16, true)`  emits as `WordPipe_w16_registered`
  *   - two `WordPipe(8, false)` instances share one emitted module
  */
class WordPipe(width: Int, registered: Boolean)
    extends Module(param("w", width), param("registered", registered)):
  // `input` and `output` are Verilog keywords; R1 emits port names verbatim, so
  // a port may not be named one of them.
  val dataIn = In(UInt(width))
  val dataOut = Out(UInt(width))
  dataOut := dataIn

/** The explicit name argument replaces the complete derived name. */
class ControlPipe(width: Int)
    extends Module(param("w", width), moduleName("NamedControlPipe")):
  val dataIn = In(UInt(width))
  val dataOut = Out(UInt(width))
  dataOut := dataIn

/** A no-argument top suitable for the generic `Emit` command. Its manifest is
  * child-first and contains one file per distinct module identity.
  *
  * R23 — each instance's data ports are connected here: inputs by assignment,
  * outputs by reading the `<instance>_<port>` net the instance drives. */
class ParameterizedModules extends Module:
  given ClockDomain = ClockDomain.noClock

  val byteIn = In(UInt(8))
  val wordIn = In(UInt(16))
  val controlIn = In(UInt(4))
  val byteOut = Out(UInt(8))
  val wordOut = Out(UInt(16))
  val controlOut = Out(UInt(4))

  val bytePipeA = inst(new WordPipe(8, registered = false))
  val bytePipeB = inst(new WordPipe(8, registered = false))
  val registeredWordPipe = inst(new WordPipe(16, registered = true))
  val controlPipe = inst(new ControlPipe(4))

  bytePipeA.dataIn := byteIn
  bytePipeB.dataIn := bytePipeA.dataOut
  registeredWordPipe.dataIn := wordIn
  controlPipe.dataIn := controlIn

  byteOut := bytePipeB.dataOut
  wordOut := registeredWordPipe.dataOut
  controlOut := controlPipe.dataOut
