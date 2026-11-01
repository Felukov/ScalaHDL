package scalahdl.golden

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.sys.process.*

import scalahdl.core.{CdcPrimitive, Expr, LitKind, MemoryKind}
import scalahdl.diag.{Code, ValidationFailed}
import scalahdl.emit.Emission
import scalahdl.examples.{AsyncFifo, FifoPayload}
import scalahdl.elab.Module
import scalahdl.dsl.{ClockDomain, inst}

/** Appendix A / M12 integration coverage, including the hand-written
  * Verilator testbench that exercises unrelated write/read clock rates. */
final class AsyncFifoSuite extends munit.FunSuite:

  private def emitted() =
    val design = (new AsyncFifo()).elaborateDesign()
    new Emission("0.1.1").emit(design)

  private def walkExpr(expr: Expr): List[Expr] = expr :: (expr match
    case Expr.UnOp(_, arg) => walkExpr(arg)
    case Expr.BinOp(_, lhs, rhs) => walkExpr(lhs) ++ walkExpr(rhs)
    case Expr.Mux(sel, whenTrue, whenFalse) =>
      walkExpr(sel) ++ walkExpr(whenTrue) ++ walkExpr(whenFalse)
    case Expr.Slice(base, _, _) => walkExpr(base)
    case Expr.Index(base, index) => walkExpr(base) ++ walkExpr(index)
    case Expr.Field(base, _) => walkExpr(base)
    case Expr.Cat(parts) => parts.flatMap(walkExpr)
    case Expr.TypeCast(_, inner) => walkExpr(inner)
    case Expr.Cdc(_, inner, _) => walkExpr(inner)
    case _ => Nil)

  private def moduleExpressions(module: scalahdl.core.Module): List[Expr] =
    def statements(stmts: List[scalahdl.core.Stmt]): List[Expr] = stmts.flatMap {
      case scalahdl.core.Stmt.Assign(_, expr, _, _) => walkExpr(expr)
      case scalahdl.core.Stmt.When(cond, yes, no, _) =>
        walkExpr(cond) ++ statements(yes) ++ statements(no)
      case scalahdl.core.Stmt.MemWrite(_, addr, data, _) => walkExpr(addr) ++ walkExpr(data)
      // R27 — unreachable here (only driver blocks are walked, and a check is
      // not in one), and correct the day it is not: a check's condition is an
      // expression like any other. `Stmt.Switch` remains unhandled, as before.
      case scalahdl.core.Stmt.Check(_, cond, _, _) => walkExpr(cond)
    }
    module.drivers.flatMap(d => statements(d.stmts)) ++
      module.memories.flatMap(m =>
        m.reads.flatMap(r => walkExpr(r.address)) ++
        m.writes.flatMap(w => walkExpr(w.address) ++ walkExpr(w.data) ++ w.enable.toList.flatMap(walkExpr)))

  test("Appendix A findings elaborate structurally without an unsafe hatch"):
    val fifo = new AsyncFifo()
    val module = fifo.elaborate()
    val expressions = moduleExpressions(module)

    assertEquals(module.name, "AsyncFifo_d16")
    assertEquals(module.memories.map(_.kind), List(MemoryKind.LutRam))
    assertEquals(module.memories.head.reads.map(_.domain.clkName), List("clkB"))
    assertEquals(module.memories.head.writes.map(_.domain.clkName), List("clkA"))
    assert(module.memories.head.writes.head.enable.nonEmpty)

    val primitives = expressions.collect { case Expr.Cdc(p, _, _) => p }
    assertEquals(primitives.count(_.isInstanceOf[CdcPrimitive.SyncChain]), 2)
    assert(!primitives.exists(_.isInstanceOf[CdcPrimitive.Unsafe]))

    val expressionBitSelects = expressions.collect {
      case Expr.Index(base, _) if !base.isInstanceOf[Expr.Ref] => base
      case Expr.Slice(base, _, _) if !base.isInstanceOf[Expr.Ref] => base
    }
    assertEquals(expressionBitSelects, Nil)

    val inferredOnes = expressions.collect {
      // R6.4 — a resolved literal carries the kind it took from its context
      // beside the width, so the pointer inherits `Unsigned` here.
      case Expr.BinOp("+", Expr.Ref(_), Expr.Lit(1, Some(5), Some(LitKind.Unsigned))) => 1
    }
    assertEquals(inferredOnes.size, 2)

  test("emission is one deterministically named file and documents all safe crossings"):
    val first = emitted()
    val second = emitted()
    assertEquals(first, second)
    assertEquals(first.files.map(_._1), List("AsyncFifo_d16.v"))
    assertEquals(first.manifest, "AsyncFifo_d16.v\n")
    assert(first.reports.head._2.contains("DualDomainMemory(memory)"))
    assert(first.reports.head._2.contains("via SyncChain(2)"))
    assert(first.reports.head._2.contains("Unsafe\n(none)"))
    assert(!first.files.head._2.contains("ASYNC_REG:"))

  test("payload stays identity-only and unequal payloads conflict under AsyncFifo_d16"):
    class Top extends Module:
      given ClockDomain = scalahdl.clock.ClockDomain.noClock
      val bytes = inst(new AsyncFifo(16, FifoPayload(8)))
      val words = inst(new AsyncFifo(16, FifoPayload(32)))

    assertEquals(new AsyncFifo(16, FifoPayload(8)).moduleName, "AsyncFifo_d16")
    val error = intercept[ValidationFailed]((new Top).elaborateDesign())
    assert(error.diagnostics.exists(_.code == Code.MODULE_IDENTITY_CONFLICT))

  test("emitted Verilog passes the hand-written asynchronous Verilator testbench"):
    assume(Seq("sh", "-c", "command -v verilator >/dev/null 2>&1").! == 0,
      "Verilator not on PATH; skipping the asynchronous Verilator testbench (M12)")

    val temp = Files.createTempDirectory("scalahdl-async-fifo-")
    val verilog = temp.resolve("AsyncFifo_d16.v")
    Files.writeString(verilog, emitted().files.head._2, StandardCharsets.UTF_8)
    val tbResource = Option(getClass.getResourceAsStream("/scalahdl/verilator/async_fifo_tb.cpp"))
      .getOrElse(fail("missing async_fifo_tb.cpp resource"))
    val tb = temp.resolve("async_fifo_tb.cpp")
    try Files.copy(tbResource, tb) finally tbResource.close()

    val command = Seq(
      "verilator", "--cc", "--exe", "--build", "--top-module", "AsyncFifo_d16",
      "-Wno-fatal", verilog.toString, tb.toString)
    val buildOutput = new StringBuilder
    val buildCode = Process(command, temp.toFile).!(ProcessLogger(
      line => buildOutput.append(line).append('\n'),
      line => buildOutput.append(line).append('\n')))
    assertEquals(buildCode, 0, buildOutput.toString)

    val simulation = Process(Seq(temp.resolve("obj_dir/VAsyncFifo_d16").toString), temp.toFile).!!
    assert(simulation.contains("PASS: transferred 200 words in order"), simulation)
