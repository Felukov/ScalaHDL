package scalahdl.emit

import scalahdl.core.MemoryKind

/** R15.7 — Whose synthesis tool the emitted Verilog is written for, and so the
  * words a memory's hint is written in.
  *
  * {{{
  *   kind        Xilinx (Vivado)            Altera (Quartus)
  *   Registers   ram_style = "registers"    ramstyle = "logic"
  *   LutRam      ram_style = "distributed"  ramstyle = "MLAB"
  *   BlockRam    ram_style = "block"        ramstyle = "M10K"   (the family's block)
  * }}}
  *
  * Emission names one vendor or none ([[Emission]]); with none, a memory
  * carries no hint and the tool infers from the access pattern alone. A tool
  * ignores the other tool's attribute without failing, so a hint in the wrong
  * words is lost in silence — which is why a hint is written only for a vendor
  * that was named. */
enum Vendor:
  /** Vivado, which reads `ram_style`. */
  case Xilinx
  /** Quartus, which reads `ramstyle` and names its block memory by device
    * family. */
  case Altera(family: AlteraFamily)

  /** The attribute this vendor's tool reads for a memory of `kind`. */
  def memoryAttribute(kind: MemoryKind): String = this match
    case Xilinx => kind match
      case MemoryKind.Registers => "ram_style = \"registers\""
      case MemoryKind.LutRam    => "ram_style = \"distributed\""
      case MemoryKind.BlockRam  => "ram_style = \"block\""
    case Altera(family) => kind match
      case MemoryKind.Registers => "ramstyle = \"logic\""
      case MemoryKind.LutRam    => "ramstyle = \"MLAB\""
      case MemoryKind.BlockRam  => s"ramstyle = \"${family.blockMemory}\""

/** An Altera device family, and what Quartus calls its block memory there — a
  * name the device does not have is ignored like any unknown attribute. A
  * family is added when a design asks for it. */
enum AlteraFamily(val blockMemory: String):
  /** The DE0-CV's device. */
  case CycloneV extends AlteraFamily("M10K")
