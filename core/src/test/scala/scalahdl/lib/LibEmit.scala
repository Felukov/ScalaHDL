package scalahdl.lib

import scalahdl.elab.Module
import scalahdl.emit.Emission

/** Writes a `scalahdl.lib` component's Verilog and reports to a directory, for
  * `tools/lib-sim.sh` to hand to Verilator with the component's testbench.
  *
  *   LibEmit Skid <width> <output-directory>
  *   LibEmit SyncFifo <depth> <width> <output-directory>
  */
object LibEmit:

  def main(args: Array[String]): Unit =
    val (component, directory): (Module, String) = args.toList match
      case List("Skid", width, directory) => (new Skid(width.toInt), directory)
      case List("SyncFifo", depth, width, directory) =>
        (new SyncFifo(depth.toInt, width.toInt), directory)
      case _ =>
        System.err.println(
          "Usage: scalahdl.lib.LibEmit Skid <width> <output-directory>\n" +
          "       scalahdl.lib.LibEmit SyncFifo <depth> <width> <output-directory>")
        sys.exit(2)
    val emission = new Emission()
    emission.writeTo(emission.emit(component.elaborateDesign()), directory)
    println(s"Wrote $directory/${component.moduleName}.v")
