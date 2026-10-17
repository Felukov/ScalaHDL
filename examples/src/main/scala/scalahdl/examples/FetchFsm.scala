package scalahdl.examples

import scalahdl.clock.ResetKind
import scalahdl.core.ResetPolarity.ActiveLow
import scalahdl.dsl.{*, given}
import scalahdl.elab.Module

/** R22 — the PicoRV32-compatible fetch controller, written as one `switch`.
  *
  * The same FSM as four independent `when` blocks is behaviorally equivalent,
  * but the source then does not say that the four are one partition, the
  * selector is repeated four times, and the backend has no way to emit a
  * native `case`. Here the partition is the construct, and each state's
  * sequential behavior — including the `when (mem_ready)` inside two of them —
  * is unchanged.
  *
  * `FetchFsmReference` below is that original `when` form, kept so the two can
  * be simulated against each other rather than compared by reading. */
class FetchFsm extends Module:
  val clk = In(Clock())
  val resetn = In(Reset(ActiveLow))
  val mem_ready = In(Bool())
  val mem_rdata = In(UInt(32))

  val mem_valid = Out(Bool())
  val mem_instr = Out(Bool())
  val mem_addr = Out(UInt(32))
  val instruction = Out(UInt(32))
  val state = Out(UInt(2))

  given defaultDomain: ClockDomain =
    ClockDomain(clk, resetn, ResetKind.Sync, ActiveLow)

  // Reset is a real state rather than an alias for request, so reset release
  // has one unambiguous first transition.
  val stateReset = 0.U(2)
  val stateRequest = 1.U(2)
  val stateWait = 2.U(2)
  val stateDecode = 3.U(2)

  val fetchState = Reg(UInt(2), init = stateReset)
  val pc = Reg(UInt(32), init = 0.U(32))
  val fetched = Reg(UInt(32), init = 0.U(32))

  switch(fetchState) {
    is(stateReset) {
      fetchState := stateRequest
    }
    is(stateRequest) {
      fetchState := stateWait
      when(mem_ready) {
        fetched := mem_rdata
        fetchState := stateDecode
      }
    }
    is(stateWait) {
      when(mem_ready) {
        fetched := mem_rdata
        fetchState := stateDecode
      }
    }
    is(stateDecode) {
      // No execute stage yet: decode is an observable cycle that advances the
      // sequential PC before issuing the next fetch.
      pc := pc + 4.U(32)
      fetchState := stateRequest
    }
  }

  mem_valid := (fetchState === stateRequest) | (fetchState === stateWait)
  mem_instr := mem_valid
  mem_addr := pc
  instruction := fetched
  state := fetchState

/** R25 — the same controller again, declared as a machine.
  *
  * Read against `FetchFsm` above, this is the requirement in one screen: the
  * states are a named encoding rather than four literals, each state's arm says
  * `goto` where the switch said `fetchState :=`, and the machine's shape is one
  * dispatch rather than a partition the reader assembles. The emitted Verilog
  * is byte-identical to `FetchMachineReference` below — the same design, in the
  * same declaration order, written as a `switch` — because an `Fsm` *is* that
  * switch, plus a graph the elaborator checks and the `.fsm.md` report draws.
  *
  * The order is why the reference exists rather than `FetchFsm` serving as one:
  * an `Fsm` declares its register where the machine is written, so `pc` and
  * `fetched` — which its body assigns — are declared above it, while `FetchFsm`
  * declares `fetchState` first and writes the switch later. R2 makes
  * declaration order signal order, so that difference is visible in the output
  * and is not R25's doing.
  *
  * What the checks buy here is small and real: delete the `goto` in `Decode`
  * and this stops elaborating with "state `Decode` has no way out", where the
  * switch form emits a machine that silently locks up after one instruction. */
object FetchState extends Encoding(2):
  val Reset, Request, Wait, Decode = Value

class FetchMachine extends Module:
  val clk = In(Clock())
  val resetn = In(Reset(ActiveLow))
  val mem_ready = In(Bool())
  val mem_rdata = In(UInt(32))

  val mem_valid = Out(Bool())
  val mem_instr = Out(Bool())
  val mem_addr = Out(UInt(32))
  val instruction = Out(UInt(32))
  val state = Out(UInt(2))

  given defaultDomain: ClockDomain =
    ClockDomain(clk, resetn, ResetKind.Sync, ActiveLow)

  val pc = Reg(UInt(32), init = 0.U(32))
  val fetched = Reg(UInt(32), init = 0.U(32))

  val fetchState = Fsm(FetchState, init = FetchState.Reset) {
    in(FetchState.Reset) {
      goto(FetchState.Request)
    }
    in(FetchState.Request) {
      goto(FetchState.Wait)
      when(mem_ready) {
        fetched := mem_rdata
        goto(FetchState.Decode)
      }
    }
    in(FetchState.Wait) {
      when(mem_ready) {
        fetched := mem_rdata
        goto(FetchState.Decode)
      }
      stay()
    }
    in(FetchState.Decode) {
      pc := pc + 4.U(32)
      goto(FetchState.Request)
    }
  }

  mem_valid := (fetchState === FetchState.Request) | (fetchState === FetchState.Wait)
  mem_instr := mem_valid
  mem_addr := pc
  instruction := fetched
  state := fetchState

/** `FetchMachine` written as an R22 `switch`, in the same declaration order:
  * the reference for R25's byte-identity claim.
  *
  * Every line below is what the machine above elaborates to. Reading the two
  * together is the clearest statement of what R25 does and does not do: the
  * arms, their order, the nesting, and the assignments are the same, and what
  * the machine adds is that `Wait`'s hold is written down, that the four states
  * are checked to be reachable and to have exits, and that the graph exists as
  * something other than prose. */
class FetchMachineReference extends Module:
  val clk = In(Clock())
  val resetn = In(Reset(ActiveLow))
  val mem_ready = In(Bool())
  val mem_rdata = In(UInt(32))

  val mem_valid = Out(Bool())
  val mem_instr = Out(Bool())
  val mem_addr = Out(UInt(32))
  val instruction = Out(UInt(32))
  val state = Out(UInt(2))

  given defaultDomain: ClockDomain =
    ClockDomain(clk, resetn, ResetKind.Sync, ActiveLow)

  val pc = Reg(UInt(32), init = 0.U(32))
  val fetched = Reg(UInt(32), init = 0.U(32))
  val fetchState = Reg(FetchState(), init = FetchState.Reset)

  switch(fetchState) {
    is(FetchState.Reset) {
      fetchState := FetchState.Request
    }
    is(FetchState.Request) {
      fetchState := FetchState.Wait
      when(mem_ready) {
        fetched := mem_rdata
        fetchState := FetchState.Decode
      }
    }
    is(FetchState.Wait) {
      when(mem_ready) {
        fetched := mem_rdata
        fetchState := FetchState.Decode
      }
    }
    is(FetchState.Decode) {
      pc := pc + 4.U(32)
      fetchState := FetchState.Request
    }
  }

  mem_valid := (fetchState === FetchState.Request) | (fetchState === FetchState.Wait)
  mem_instr := mem_valid
  mem_addr := pc
  instruction := fetched
  state := fetchState

/** The same controller as repeated equality-tested `when` blocks — the form
  * R22 replaces. It exists only as the oracle's reference: `SwitchFsmOracle`
  * runs both from one clock and requires their outputs to agree cycle by
  * cycle, so "the switch preserves each state's sequential behavior" is a
  * simulation result rather than a claim about two listings. */
class FetchFsmReference extends Module:
  val clk = In(Clock())
  val resetn = In(Reset(ActiveLow))
  val mem_ready = In(Bool())
  val mem_rdata = In(UInt(32))

  val mem_valid = Out(Bool())
  val mem_instr = Out(Bool())
  val mem_addr = Out(UInt(32))
  val instruction = Out(UInt(32))
  val state = Out(UInt(2))

  given defaultDomain: ClockDomain =
    ClockDomain(clk, resetn, ResetKind.Sync, ActiveLow)

  val stateReset = 0.U(2)
  val stateRequest = 1.U(2)
  val stateWait = 2.U(2)
  val stateDecode = 3.U(2)

  val fetchState = Reg(UInt(2), init = stateReset)
  val pc = Reg(UInt(32), init = 0.U(32))
  val fetched = Reg(UInt(32), init = 0.U(32))

  when(fetchState === stateReset) {
    fetchState := stateRequest
  }
  when(fetchState === stateRequest) {
    fetchState := stateWait
    when(mem_ready) {
      fetched := mem_rdata
      fetchState := stateDecode
    }
  }
  when((fetchState === stateWait) & mem_ready) {
    fetched := mem_rdata
    fetchState := stateDecode
  }
  when(fetchState === stateDecode) {
    pc := pc + 4.U(32)
    fetchState := stateRequest
  }

  mem_valid := (fetchState === stateRequest) | (fetchState === stateWait)
  mem_instr := mem_valid
  mem_addr := pc
  instruction := fetched
  state := fetchState

/** R22 — alternatives produced by an ordinary Scala iterator.
  *
  * The table is Scala data; the `for` that walks it is not hardware and never
  * enters the IR (R2). What lands is the same ordered first-class switch a
  * reader would have written out, which is the property `SwitchSurfaceSuite`
  * asserts on the IR and this example demonstrates end to end. The `default`
  * arm is what makes the two wires complete (R11) with no assignment before
  * the switch. */
class DecodeTable extends Module:
  given ClockDomain = scalahdl.clock.ClockDomain.noClock

  val opcode = In(UInt(3))
  val result = Out(UInt(8))
  val illegal = Out(Bool())

  /** funct → result. Ordinary Scala: the generator, not the hardware. */
  val encodings: List[(Int, Int)] = List(0 -> 11, 1 -> 22, 2 -> 33, 3 -> 44)

  switch(opcode) {
    for (code, value) <- encodings do
      is(code.U(3)) {
        result := value.U(8)
        illegal := 0.U(1)
      }

    // Two opcodes sharing one body, alongside the generated alternatives.
    is(6.U(3), 7.U(3)) {
      result := 255.U(8)
      illegal := 0.U(1)
    }

    default {
      result := 0.U(8)
      illegal := 1.U(1)
    }
  }
