package scalahdl.golden

import scalahdl.examples.NestedModules
import scalahdl.emit.Emission

class NestedModulesSuite extends munit.FunSuite:

  private val expectedFiles = List(
    "NestedLeaf_i0.v",
    "NestedLeaf_i1.v",
    "NestedBranch_i0.v",
    "NestedLeaf_i2.v",
    "NestedLeaf_i3.v",
    "NestedBranch_i1.v",
    "NestedModules.v"
  )

  test("nested hierarchy emits DFS children before parents in declaration order"):
    val design = (new NestedModules).elaborateDesign()

    assertEquals(design.top.instances.map(instance => instance.name -> instance.target), List(
      "leftBranch" -> "NestedBranch_i0",
      "rightBranch" -> "NestedBranch_i1"
    ))

    val branches = design.modules.filter(_.name.startsWith("NestedBranch_"))
    assertEquals(branches.map(branch => branch.name -> branch.instances.map(_.target)), List(
      "NestedBranch_i0" -> List("NestedLeaf_i0", "NestedLeaf_i1"),
      "NestedBranch_i1" -> List("NestedLeaf_i2", "NestedLeaf_i3")
    ))

    val emitted = new Emission("0.1.1")
      .emit(design, "scalahdl.examples.NestedModules")
    assertEquals(emitted.files.map(_._1), expectedFiles)
    assertEquals(emitted.manifest, expectedFiles.mkString("\n") + "\n")
