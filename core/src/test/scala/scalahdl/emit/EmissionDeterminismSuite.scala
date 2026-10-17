package scalahdl.emit

import scalahdl.core.{Design, Instance, Loc, Module}

class EmissionDeterminismSuite extends munit.FunSuite:

  private def module(name: String, children: List[String] = Nil): Module =
    val instances = children.zipWithIndex.map { case (target, index) =>
      Instance(s"child$index", target, Nil, Loc.NoLoc)
    }
    Module(name, Nil, Nil, Nil, instances, Nil, Loc.NoLoc)

  private def emittedNames(design: Design): List[String] =
    new Emission("test").emit(design, "DeterminismTest").files.map(_._1)

  test("emission computes DFS post-order from top with declaration-order ties"):
    val leafA = module("LeafA")
    val branchA = module("BranchA", List("LeafA"))
    val leafB = module("LeafB")
    val branchB = module("BranchB", List("LeafB"))
    val top = module("Top", List("BranchA", "BranchB"))

    // Deliberately scramble Design.modules: output order must come from the IR
    // reachability graph, never from collection or construction order.
    val design = Design(top, List(top, leafB, branchA, branchB, leafA))
    val emitted = new Emission("test").emit(design, "DeterminismTest")

    val expected = List("LeafA.v", "BranchA.v", "LeafB.v", "BranchB.v", "Top.v")
    assertEquals(emitted.files.map(_._1), expected)
    // R5.1 per module, then R29 per module, then R29's one design roll-up —
    // each group in the same DFS post-order the files are in.
    assertEquals(emitted.reports.map(_._1),
      expected.map(_.stripSuffix(".v") + ".cdc.rpt") ++
        expected.map(_.stripSuffix(".v") + ".levels.rpt") :+ "design.levels.rpt")
    assertEquals(emitted.manifest, expected.mkString("\n") + "\n")

  test("shared children are emitted once at their first DFS encounter"):
    val shared = module("Shared")
    val left = module("Left", List("Shared"))
    val right = module("Right", List("Shared"))
    val top = module("Top", List("Left", "Right"))
    val design = Design(top, List(right, shared, top, left))

    assertEquals(emittedNames(design), List("Shared.v", "Left.v", "Right.v", "Top.v"))

  test("adding one child preserves the relative order of unrelated files"):
    val leafA = module("LeafA")
    val branchA = module("BranchA", List("LeafA"))
    val branchB = module("BranchB")
    val top = module("Top", List("BranchA", "BranchB"))
    val before = emittedNames(Design(top, List(branchB, top, leafA, branchA)))

    val added = module("Added")
    val changedBranchA = module("BranchA", List("LeafA", "Added"))
    val changedTop = module("Top", List("BranchA", "BranchB"))
    val after = emittedNames(Design(changedTop,
      List(added, changedTop, branchB, changedBranchA, leafA)))

    assertEquals(after.filterNot(_ == "Added.v"), before)
