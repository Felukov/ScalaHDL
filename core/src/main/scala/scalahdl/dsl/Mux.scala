package scalahdl.dsl

import scalahdl.core.*
import scalahdl.elab.Module

/** `Mux(sel, a, b)` is an anonymous expression tree (R2), emitted inline at
  * its use site. It never invents a signal or declaration. The explicit Mux
  * call keeps the decoder visible while preserving the invariant that emitted
  * wire count equals user-named Scala signal count.
  *
  * R21 — both arms are `Expr`, so a signal, an anonymous expression, or a
  * literal is admissible in either. Requiring a named signal in the true arm
  * made `Mux(sel, a + b, b)` a *Scala compile* error, which every ALU output
  * mux runs into (Appendix D finding 3). The arms must agree in type and width
  * (`WidthPhase`); a literal arm takes the other arm's width, and two literal
  * arms have no context and are an error (R21.7). */
def Mux(sel: Expr, whenTrue: Expr, whenFalse: Expr): Expr =
  Expr.Mux(sel, whenTrue, whenFalse)
