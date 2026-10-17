package scalahdl.naming

import scalahdl.dsl.{*, given}
import scalahdl.elab.{Module, ModuleArg, moduleName, modulePrefix, namedParam, param}
import scalahdl.core.Type
import scalahdl.clock.ClockDomain
import scalahdl.diag.{Code, ValidationFailed}
import scalahdl.emit.Emission

class ModuleNamingSuite extends munit.FunSuite:

  given ClockDomain = ClockDomain.noClock

  enum Mode:
    case Burst, Single

  final case class Payload(width: Int)

  class Fifo(depth: Int, width: Int, registered: Boolean = false,
             offset: Int = 0, mode: Mode = Mode.Single)
      extends Module(param("d", depth), param("w", width),
        param("registered", registered), param("off", offset), param("mode", mode)):
    val push = In(Type.Bool)
    val full = Out(Type.Bool)
    full := push

  test("Int, Boolean, enum, and negative values have stable derived encodings"):
    assertEquals(new Fifo(16, 8).moduleName, "Fifo_d16_w8_off0_mode_Single")
    assertEquals(new Fifo(16, 8, registered = true, offset = -4, mode = Mode.Burst).moduleName,
      "Fifo_d16_w8_registered_offn4_mode_Burst")

  test("identical parameter instances deduplicate to one emitted module"):
    class Top extends Module:
      val start = In(Type.Bool)
      val a = inst(new Fifo(16, 8))
      val b = inst(new Fifo(16, 8))
      a.push := start
      b.push := start
    val design = (new Top).elaborateDesign()
    assertEquals(design.modules.map(_.name), List("Fifo_d16_w8_off0_mode_Single", "Top"))
    assertEquals(design.top.instances.map(_.target),
      List("Fifo_d16_w8_off0_mode_Single", "Fifo_d16_w8_off0_mode_Single"))
    val emitted = new Emission("test").emit(design, "Top")
    assertEquals(emitted.files.map(_._1), List("Fifo_d16_w8_off0_mode_Single.v", "Top.v"))

  test("derived names are local and independent of unrelated instances"):
    class First extends Module:
      val start = In(Type.Bool)
      val a = inst(new Fifo(16, 8))
      a.push := start
    class Second extends Module:
      val start = In(Type.Bool)
      val unrelated = inst(new Fifo(4, 32))
      val a = inst(new Fifo(16, 8))
      unrelated.push := start
      a.push := start
    val firstTarget = (new First).elaborateDesign().top.instances.head.target
    val secondTarget = (new Second).elaborateDesign().top.instances.last.target
    assertEquals(firstTarget, secondTarget)

  test("non-contributing parameters remain out of names and conflicts are diagnosed"):
    class AsyncFifo(depth: Int, payload: Payload)
        extends Module(param("d", depth), param("payload", payload))
    class Top extends Module:
      val bytes = inst(new AsyncFifo(16, Payload(8)))
      val words = inst(new AsyncFifo(16, Payload(32)))
    assertEquals(new AsyncFifo(16, Payload(8)).moduleName, "AsyncFifo_d16")
    val ex = intercept[ValidationFailed]((new Top).elaborateDesign())
    assert(ex.diagnostics.exists(_.code == Code.MODULE_IDENTITY_CONFLICT))
    assert(ex.getMessage.contains("namedParam"))

  test("namedParam opts custom values into the name and resolves identity"):
    class AsyncFifo(depth: Int, payload: Payload)
        extends Module(param("d", depth), namedParam("payload", payload, s"w${payload.width}"))
    class Top extends Module:
      val bytes = inst(new AsyncFifo(16, Payload(8)))
      val words = inst(new AsyncFifo(16, Payload(32)))
    val design = (new Top).elaborateDesign()
    assertEquals(design.top.instances.map(_.target),
      List("AsyncFifo_d16_payload_w8", "AsyncFifo_d16_payload_w32"))

  test("explicit moduleName argument overrides derivation and is collision checked"):
    class Renamed(depth: Int, payload: Payload, emitted: String)
        extends Module(param("d", depth), param("payload", payload), moduleName(emitted))
    class Good extends Module:
      val a = inst(new Renamed(16, Payload(8), "MyFifo"))
    val good = (new Good).elaborateDesign()
    assertEquals(good.top.instances.head.target, "MyFifo")
    assert(good.modules.exists(_.name == "MyFifo"))

    class Bad extends Module:
      val a = inst(new Renamed(16, Payload(8), "MyFifo"))
      val b = inst(new Renamed(16, Payload(32), "MyFifo"))
    val ex = intercept[ValidationFailed]((new Bad).elaborateDesign())
    assert(ex.diagnostics.exists(_.code == Code.MODULE_IDENTITY_CONFLICT))

  test("width-carrying hardware types contribute their width"):
    class Typed(t: Type) extends Module(param("w", t))
    assertEquals(new Typed(Type.UInt(12)).moduleName, "Typed_w12")

  // -- R16.2, the group prefix -------------------------------------------

  abstract class M68kModule(args: ModuleArg*) extends Module(modulePrefix("m68k") +: args*)

  test("a group prefix goes in front of the derived name and its parameters"):
    class Fetch extends M68kModule
    class Queue(depth: Int) extends M68kModule(param("d", depth))
    assertEquals((new Fetch).moduleName, "m68k_Fetch")
    assertEquals((new Queue(16)).moduleName, "m68k_Queue_d16")

  test("a prefix reaches the emitted module and its file, and two groups may share a class name"):
    class Fifo(depth: Int) extends M68kModule(param("d", depth)):
      val push = In(Type.Bool)
      val full = Out(Type.Bool)
      full := push
    class Top extends Module(modulePrefix("soc")):
      val start = In(Type.Bool)
      val a = inst(new Fifo(16))
      a.push := start
    val design = (new Top).elaborateDesign()
    assertEquals(design.modules.map(_.name), List("m68k_Fifo_d16", "soc_Top"))
    val emitted = new Emission("test").emit(design, "soc_Top")
    assertEquals(emitted.files.map(_._1), List("m68k_Fifo_d16.v", "soc_Top.v"))

  test("an explicit moduleName is a whole name and takes no prefix"):
    class Renamed extends M68kModule(moduleName("cpu_core"))
    assertEquals((new Renamed).moduleName, "cpu_core")

  test("a module may carry only one prefix, and the error names both"):
    class Twice extends Module(modulePrefix("m68k"), modulePrefix("soc"))
    val ex = intercept[IllegalArgumentException](new Twice)
    assert(ex.getMessage.contains("m68k"), ex.getMessage)
    assert(ex.getMessage.contains("soc"), ex.getMessage)

  test("a prefix is part of a Verilog identifier"):
    for bad <- List("", "m68k-cpu", "m68k cpu", "68k", "m68k.cpu") do
      intercept[IllegalArgumentException](modulePrefix(bad))
    class Underscored extends Module(modulePrefix("m68k_v2"))
    assertEquals((new Underscored).moduleName, "m68k_v2_Underscored")
