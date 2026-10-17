package scalahdl.core

/** R10 — Port direction. Applied at the declaration site with `In`/`Out`. */
enum Direction:
  case In
  case Out
  def verilog: String = this match
    case In  => "input"
    case Out => "output"
