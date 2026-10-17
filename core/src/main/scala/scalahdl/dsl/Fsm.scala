package scalahdl.dsl

import scalahdl.core.*
import scalahdl.elab.Module

/** R25 — A state machine is one declaration.
  *
  * ```scala
  * object State extends Encoding(4):
  *   val Reset, Request, Wait, Decode, Trap = Value
  *
  * val state = Fsm(State, init = State.Reset) {
  *   in(State.Reset)   { goto(State.Request) }
  *   in(State.Request) {
  *     goto(State.Wait)
  *     when(mem_ready) {
  *       instruction := mem_rdata
  *       goto(State.Decode)
  *     }
  *   }
  *   terminal(State.Trap)
  *   default { goto(State.Trap) }
  * }
  * ```
  *
  * `Fsm` declares the state register — R1 names it from the `val`, exactly as
  * `Reg` does — and **returns it**. `state === State.Trap` and
  * `Mux(state === State.LdRs2, a, b)` read it like any other signal; there is
  * no handle to unwrap and no use site to change.
  *
  * Underneath, the machine *is* an R22 `switch` on that register: `in` opens an
  * `is` alternative, `goto` assigns the register, and `default` is R22's own.
  * The IR and the emitted Verilog are what the hand-written switch produces,
  * byte for byte. What the construct adds is the graph — every state, which are
  * terminal, and every transition with the guard it was written under — which
  * the elaborator checks and the `<Module>.fsm.md` report draws.
  *
  * The body is an elaboration scope, not a data structure, on exactly R22's
  * terms: `in`, `terminal`, `goto`, and `stay` enlist into the machine open on
  * the builder, so each state's body may be an ordinary Scala method and a
  * `for` may produce states. That is what lets a machine read as a one-screen
  * dispatch table over named steps, and it is why `goto` outside a machine is
  * an R19 diagnostic rather than a type error. */

/** `Fsm(encoding, init) { ... }` — declare a state machine and return its state
  * register.
  *
  * R13.4/R13.8 — `init` is the state a reset loads and `powerUp` the state a
  * bitstream loads, exactly as on `Reg`; a machine names at least one. In a
  * domain with no reset only `powerUp` has anywhere to go.
  *
  * R4.1 — `Fsm(State, init = State.Idle, domain = writeSide) { ... }` names the
  * domain of the state register, exactly as `Reg` does and with the same
  * precedence. It is the *machine's* domain, not each arm's: the body is one
  * `always` block on one edge, so there is nothing finer to name. */
inline def Fsm(inline encoding: Encoding, inline init: EncodingValue = null,
               inline powerUp: EncodingValue = null, domain: ClockDomain = null)
              (inline body: => Unit)
              (using m: Module, cd: ClockDomain): DslSignal =
  fsmImpl(encoding, Option(init), Option(powerUp), Source.capture, () => body, domain)

/** The whole of `Fsm` after name capture. `Source.capture` sits in argument
  * position of this one call, as `Reg`'s does, so the splice owner is the
  * user's `val` and the register takes its name. */
private def fsmImpl(encoding: Encoding, init: Option[EncodingValue],
                    powerUp: Option[EncodingValue], src: Source,
                    body: () => Unit, domain: ClockDomain)(
                    using m: Module, cd: ClockDomain): DslSignal =
  val _ = m.builder.beginFsm(src, encoding, init, powerUp,
                             m.builder.declaredDomain(domain, cd))
  body()
  m.builder.endFsm()

/** `in(state) { ... }` — one state's behaviour. An R22 `is(state)` on the
  * machine's switch, and the arm a `goto` inside it transitions from.
  *
  * R25.1 — `in(s1, s2, ...) { ... }` gives one body to several states, exactly
  * as R22's `is(v1, v2, ...)` gives one body to several values, and emits the
  * one case item with all of their labels. Each named state is still its own
  * state of the machine: it appears in the report, it must have a way out, and
  * a `goto` in the shared body records an edge from every one of them. */
inline def in(states: EncodingValue*)(inline body: => Unit)(using m: Module): Unit =
  m.builder.beginFsmState(states.toList, Loc.here)
  body
  m.builder.endFsmState()

/** R25.1 — `currentState`, the machine's own register read from inside its
  * body.
  *
  * `val state = Fsm(...) { ... state ... }` is a recursive value in Scala, so
  * the register the machine returns cannot be read while the body that
  * produces it elaborates. Outside, `state === State.Trap` is how a machine's
  * state is asked about, and this is the same register under the one name it
  * can have before the `val` exists.
  *
  * What needs it is an arm that names several states (`in(a, b)`) and differs
  * in a detail between them — which is the shape that made aliases worth
  * having, and would otherwise force a wire declared before the machine and
  * driven after it, read by a reader who has to jump twice to find out what it
  * holds. It is the register itself, so R25's rule that only `goto` writes it
  * is unchanged: assigning it here is `FSM_STATE_ASSIGNED` exactly as it is
  * anywhere else. */
inline def currentState(using m: Module): DslSignal = m.builder.fsmState(Loc.here)

/** `terminal(state)` — a state with no exit: a trap, a halt, a latched fault.
  * Emits the same empty arm an `in` with no body would, and exempts the state
  * from the "no way out" check. Declared rather than inferred, because the
  * difference between a machine that stops here and one whose author forgot
  * the transition is exactly what R25 is for. */
inline def terminal(inline state: EncodingValue)(using m: Module): Unit =
  m.builder.fsmTerminal(state, Loc.here)

/** `goto(state)` — the transition. Assigns the state register and records the
  * edge under the `when` conditions open at this point. */
inline def goto(inline state: EncodingValue)(using m: Module): Unit =
  m.builder.fsmGoto(state, Loc.here)

/** `stay()` — this arm deliberately holds. Emits nothing: R11.1 already holds a
  * register that is not assigned. What it records is that the author said so,
  * which is otherwise unwritable — and the self-loop the report draws. */
inline def stay()(using m: Module): Unit =
  m.builder.fsmStay(Loc.here)
