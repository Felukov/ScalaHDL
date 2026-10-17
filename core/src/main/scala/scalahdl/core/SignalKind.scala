package scalahdl.core

/** R17 — Signal kind. A wire is combinational; a register is clocked.
  * R11.1's "registers are exempt from completeness" reads this: an unassigned
  * path on a `Reg` means hold, not a latch. */
enum SignalKind:
  case Wire
  case Reg
