package scalahdl.core

/** R17.1 — Signals are referenced by a stable id, not by object identity.
  *
  * An opaque Int is enough: the builder hands out ids in declaration order
  * and statements/expressions reference signals through `Ref(id)`.
  * This is what keeps the IR acyclic — a driver block references its target
  * signal by id, and a signal never references its driver.
  */
opaque type SignalId = Int

object SignalId:
  def apply(n: Int): SignalId = n
  extension (id: SignalId) def value: Int = id
