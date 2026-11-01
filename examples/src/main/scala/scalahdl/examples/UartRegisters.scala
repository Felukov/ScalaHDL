package scalahdl.examples

import scalahdl.dsl.{*, given}
import scalahdl.elab.Module

/** R40 — a register map read through its field handles.
  *
  * {{{
  *   object UartRegs extends RegisterMap(...)
  *         │
  *         ├── this module      UartRegs.Command.Index.of(word)
  *         ├── the C header     #define UART_COMMAND_INDEX_SHIFT 4u
  *         └── the package      localparam COMMAND_INDEX_SHIFT = 4;
  * }}}
  *
  * The point of the example is the absence of numbers. The module says
  * `Index.of(word)` where a hand-written decoder says `word(9, 4)`, and the
  * header gets its shift from the same declaration, so the two cannot disagree.
  *
  * [[UartRegistersByHand]] is the same logic with the numbers written out. A
  * test compares the two emitted bodies, which is the evidence that a handle
  * costs no hardware: it is a name for a bit window, not a new signal. */
object UartRegs extends RegisterMap(prefix = "UART_", pkg = "uart_regs",
                                    base = 0x1000_0000, addressBits = 4):

    object Control extends Register(0):
        val Divider = bits(hi = 7, lo = 0)
        val Enable  = bit(8)

    /** The command's codes, declared as R24 declares an encoding: as its own
      * `object` in the map, so it is built with the map and its members exist
      * before any register names it. */
    object OpCodes extends CodedField(lo = 0, width = 2):
        val Send, Clear, Cancel = code

    object Command extends Register(1):
        val Op    = add(OpCodes)
        val Index = bits(hi = 9, lo = 4)

    object Status extends Register(2):
        val Busy = bit(0)

    registers(Control, Command, Status)

/** The registers read through handles. */
class UartRegisters extends Module:
    val word = In(UInt(16))

    /** The command's index, taken from the field that declares it. */
    val index = Out(UInt(6))
    /** True while the command is `Send`. */
    val send = Out(Bool())
    /** The divider, and the bit that enables the port. */
    val divider = Out(UInt(8))
    val enable = Out(Bool())

    index   := UartRegs.Command.Index.of(word)
    send    := UartRegs.Command.Op.of(word) === UartRegs.OpCodes.Send
    divider := UartRegs.Control.Divider.of(word)
    enable  := UartRegs.Control.Enable.of(word)

/** The same module with the same numbers spelled by hand — 9 and 4 for the
  * index, 2 bits at 0 for the operation, 8 bits at 0 for the divider, 8 for the
  * enable bit. Its emitted body must equal the one above. */
class UartRegistersByHand extends Module:
    val word = In(UInt(16))

    val index = Out(UInt(6))
    val send = Out(Bool())
    val divider = Out(UInt(8))
    val enable = Out(Bool())

    index   := word(9, 4)
    send    := word(1, 0) === 0.U(2)
    divider := word(7, 0)
    enable  := word(8)
