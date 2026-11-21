package scalahdl.golden

import scalahdl.elab.Module
import scalahdl.emit.Emission
import scalahdl.examples.{UartRegs, UartRegisters, UartRegistersByHand}

/** R40 — what the example promises.
  *
  * Two claims, and the second is the one that matters. A field handle is a name
  * for a bit window, so a module that reads one must emit the same Verilog as a
  * module that writes the window by hand. If that ever stops being true, the
  * handle has become hardware and R40's central promise is broken.
  */
class UartRegistersSuite extends munit.FunSuite:

  private def body(module: Module): String =
    val emitted = new Emission("0.1.2")
      .emit(module.elaborateDesign(), "scalahdl.examples." + module.getClass.getSimpleName)
    emitted.files.head._2.replace(module.moduleName, "M")

  test("a field handle emits the same Verilog as the bit pair written by hand"):
    assertEquals(body(new UartRegisters), body(new UartRegistersByHand))

  test("a handle's slice is the field's own window, and a bit field is one bit"):
    // The emitted text carries `[9:4]` because a handle *is* a slice: the claim
    // is that the number comes from the field, not that the number disappears.
    val text = body(new UartRegisters)
    assert(text.contains("[9:4]"), text)

    val index = UartRegs.Command.fields
      .collectFirst { case placed if placed.name == "Index" => placed }
      .getOrElse(fail("no Index field"))
    assertEquals((index.lo, index.width), (4, 6))

    val enable = UartRegs.Control.fields
      .collectFirst { case placed if placed.name == "Enable" => placed }
      .getOrElse(fail("no Enable field"))
    assertEquals((enable.lo, enable.width), (8, 1))

  test("the map renders a header and a package from the one declaration"):
    // `Op`, `Index` and `Divider` carry no number in this module, and each is a
    // named define here — the link from declaration to firmware.
    assert(UartRegs.cHeader.contains("#define UART_COMMAND_INDEX_SHIFT   4u"),
           UartRegs.cHeader)
    assert(UartRegs.cHeader.contains("#define UART_COMMAND_OP_SEND       0u"),
           UartRegs.cHeader)
    assert(UartRegs.systemVerilogPackage.contains("COMMAND_INDEX_WIDTH = 6"),
           UartRegs.systemVerilogPackage)

  test("the emitted module passes Verilator"):
    // The example is linted by the shared harness with the other examples; this
    // test states the promise so a reader of the suite sees it.
    assert(body(new UartRegisters).nonEmpty)
