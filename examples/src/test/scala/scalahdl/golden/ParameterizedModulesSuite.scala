package scalahdl.golden

import scalahdl.examples.ParameterizedModules
import scalahdl.emit.Emission

class ParameterizedModulesSuite extends munit.FunSuite:

  test("parameterized example derives names, deduplicates, and honors override"):
    val design = (new ParameterizedModules).elaborateDesign()

    assertEquals(design.top.instances.map(_.name),
      List("bytePipeA", "bytePipeB", "registeredWordPipe", "controlPipe"))
    assertEquals(design.top.instances.map(_.target), List(
      "WordPipe_w8",
      "WordPipe_w8",
      "WordPipe_w16_registered",
      "NamedControlPipe"
    ))
    assertEquals(design.modules.map(_.name), List(
      "WordPipe_w8",
      "WordPipe_w16_registered",
      "NamedControlPipe",
      "ParameterizedModules"
    ))

    val emitted = new Emission("0.1.2")
      .emit(design, "scalahdl.examples.ParameterizedModules")
    assertEquals(emitted.files.map(_._1), List(
      "WordPipe_w8.v",
      "WordPipe_w16_registered.v",
      "NamedControlPipe.v",
      "ParameterizedModules.v"
    ))
    assertEquals(emitted.manifest,
      "WordPipe_w8.v\nWordPipe_w16_registered.v\nNamedControlPipe.v\nParameterizedModules.v\n")
