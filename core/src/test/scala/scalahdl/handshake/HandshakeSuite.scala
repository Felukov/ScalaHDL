package scalahdl.handshake

import scalahdl.dsl.{*, given}
import scalahdl.elab.Module
import scalahdl.elab.Builder.ElabError
import scalahdl.emit.VerilogEmitter
import scalahdl.core.{Direction, Type}

/** R08 — `Handshake` and `Flow`, the two definitions on R31. What is tested
  * here is what the definitions say: three ports or two, their directions by
  * role, and the payload coming back typed. How an endpoint binds and how
  * `<>` decides a leaf's direction is R31's, in `InterfaceSuite`. */
class HandshakeSuite extends munit.FunSuite:

  private def emit(top: Module): String =
    new VerilogEmitter("0.1.0-SNAPSHOT").emitModule(top.elaborateDesign().top, "Top")

  test("master and slave endpoints have complementary ports, named by the val"):
    class Top extends Module:
      val in = Slave(Handshake(UInt(8)))
      val out = Master(Handshake(UInt(8)))
      in <> out

    val design = (new Top).elaborateDesign()
    val ports = design.top.ports.map(p => (p.name, p.direction, p.typeOf))
    assertEquals(ports, List(
      ("in_valid", Direction.In, Type.Bool),
      ("in_ready", Direction.Out, Type.Bool),
      ("in_payload", Direction.In, Type.UInt(8)),
      ("out_valid", Direction.Out, Type.Bool),
      ("out_ready", Direction.In, Type.Bool),
      ("out_payload", Direction.Out, Type.UInt(8))
    ))

    // A slave passed through to a master: data forward, ready back.
    val v = new VerilogEmitter("0.1.0-SNAPSHOT").emitModule(design.top, "Top")
    assert(v.contains("out_valid = in_valid;"), v)
    assert(v.contains("out_payload = in_payload;"), v)
    assert(v.contains("in_ready = out_ready;"), v)

  test("connection is symmetric at the call site"):
    class Top extends Module:
      val in = Slave(Handshake(UInt(8)))
      val out = Master(Handshake(UInt(8)))
      out <> in

    val top = (new Top).elaborate()
    assertEquals(top.drivers.size, 3)

  test("master to master is an elaboration error"):
    class Producer extends Module:
      val out = Master(Handshake(UInt(8)))
      out.valid := True
      out.payload := 7.U(8)

    val error = intercept[ElabError]:
      class Top extends Module:
        given ClockDomain = scalahdl.clock.ClockDomain.noClock
        val left = inst(new Producer)
        val right = inst(new Producer)
        left.out <> right.out
      new Top

    assert(error.getMessage.contains("both read here"), error.getMessage)
    assert(error.getMessage.contains("out_valid"), error.getMessage)

  test("mismatched payload types are an elaboration error"):
    val error = intercept[ElabError]:
      class Top extends Module:
        val in = Slave(Handshake(UInt(8)))
        val out = Master(Handshake(UInt(16)))
        in <> out
      new Top

    assert(error.getMessage.contains("shapes differ"), error.getMessage)
    assert(error.getMessage.contains("UInt(8)"), error.getMessage)
    assert(error.getMessage.contains("UInt(16)"), error.getMessage)

  case class Packet(width: Int) extends Bundle:
    val addr = UInt(width)
    val last = Bool()

  test("bundle payload uses normal R07.1 flattening and typed fields"):
    class Top extends Module:
      val in = Slave(Handshake(Packet(8)))
      val out = Master(Handshake(Packet(8)))
      in <> out

    val v = emit(new Top)
    assert(v.contains("input [7:0] in_payload_addr"), v)
    assert(v.contains("input in_payload_last"), v)
    assert(v.contains("[7:0] out_payload_addr"), v)
    assert(v.contains("out_payload_addr = in_payload_addr;"), v)
    assert(v.contains("out_payload_last = in_payload_last;"), v)

  test("bundle payload shapes must match exactly"):
    case class Other(width: Int) extends Bundle:
      val data = UInt(width)
      val last = Bool()

    val error = intercept[ElabError]:
      class Top extends Module:
        val in = Slave(Handshake(Packet(8)))
        val out = Master(Handshake(Other(8)))
        in <> out
      new Top

    assert(error.getMessage.contains("shapes differ"), error.getMessage)

  // R23 — the connection a library component is used with. `<>` records its
  // leaves the way `:=` does, so across an instance boundary they are
  // connections on the instance rather than statements in the parent.
  class Producer extends Module:
    val out = Master(Handshake(UInt(8)))
    out.valid := True
    out.payload := 7.U(8)

  class Consumer extends Module:
    val in = Slave(Handshake(UInt(8)))
    in.ready := True

  test("`<>` between two instances connects their ports"):
    class Top extends Module:
      given ClockDomain = scalahdl.clock.ClockDomain.noClock
      val producer = inst(new Producer)
      val consumer = inst(new Consumer)
      producer.out <> consumer.in

    val design = (new Top).elaborateDesign()
    val top = design.top

    // Nothing landed in the parent's own logic: a connection is not a driver.
    assertEquals(top.drivers, Nil)

    val connections = top.instances.map(i => i.name -> i.connections.map(_._1))
    assertEquals(connections, List(
      "producer" -> List("out_valid", "out_ready", "out_payload"),
      "consumer" -> List("in_valid", "in_ready", "in_payload")))

    val v = new VerilogEmitter("0.1.0-SNAPSHOT").emitModule(top, "Top")
    assert(v.contains(".out_ready(consumer_in_ready)"), v)
    assert(v.contains(".in_valid(producer_out_valid)"), v)
    assert(v.contains(".in_payload(producer_out_payload)"), v)

  // R23/R31 — a bundle payload is a bundle port, so a parent that reaches a
  // field of a child's payload must reach the *child's* pin. The field's
  // `val` is the port's own handle — the port is declared onto it — so what
  // R23 does to a port's handle at instantiation reaches the field.
  case class Framed() extends Bundle:
    val addr = UInt(25)
    val last = Bool()

  class Framer extends Module:
    val out = Master(Handshake(Framed()))
    out.valid := True
    out.payload.addr := 0.U(25)
    out.payload.last := False

  class Sinker extends Module:
    val in = Slave(Handshake(Framed()))
    in.ready := True

  test("a parent reads a field of a child's bundle payload"):
    class Top extends Module:
      given ClockDomain = scalahdl.clock.ClockDomain.noClock
      val addr = Out(UInt(25))
      val last = Out(Bool())
      val u = inst(new Framer)
      u.out.ready := True
      addr := u.out.payload.addr
      last := u.out.payload.last

    val v = emit(new Top)
    assert(v.contains("addr = u_out_payload_addr;"), v)
    assert(v.contains("last = u_out_payload_last;"), v)

  test("a parent drives a field of a child's bundle payload"):
    class Top extends Module:
      given ClockDomain = scalahdl.clock.ClockDomain.noClock
      val addr = In(UInt(25))
      val u = inst(new Sinker)
      u.in.valid := True
      u.in.payload.addr := addr
      u.in.payload.last := False

    val top = (new Top).elaborateDesign().top
    // A pin is a connection on the instance, never a statement in the parent.
    assertEquals(top.drivers, Nil)
    assertEquals(top.instances.map(_.connections.map(_._1)),
      List(List("in_valid", "in_ready", "in_payload_addr", "in_payload_last")))

  // ---- Flow: the handshake without the ready

  test("a flow is valid and payload, and nothing comes back"):
    class Top extends Module:
      val word = Master(Flow(UInt(16)))
      word.valid := True
      word.payload := 5.U(16)

    val ports = (new Top).elaborateDesign().top.ports.map(p => (p.name, p.direction, p.typeOf))
    assertEquals(ports, List(
      ("word_valid", Direction.Out, Type.Bool),
      ("word_payload", Direction.Out, Type.UInt(16))))

  class Answerer extends Module:
    val rsp = Master(Flow(Framed()))
    rsp.valid := True
    rsp.payload.addr := 1.U(25)
    rsp.payload.last := True

  class Taker extends Module:
    val rsp = Slave(Flow(Framed()))
    val addr = Out(UInt(25))
    addr := Mux(rsp.valid, rsp.payload.addr, 0.U(25))

  test("`<>` connects a flow's two pins and nothing else"):
    class Top extends Module:
      given ClockDomain = scalahdl.clock.ClockDomain.noClock
      val answerer = inst(new Answerer)
      val taker = inst(new Taker)
      answerer.rsp <> taker.rsp

    val top = (new Top).elaborateDesign().top
    assertEquals(top.drivers, Nil)
    assertEquals(top.instances.map(i => i.name -> i.connections.map(_._1)), List(
      "answerer" -> List("rsp_valid", "rsp_payload"),
      "taker" -> List("rsp_valid", "rsp_payload", "addr")))

  test("a flow and a handshake of the same payload do not connect"):
    val error = intercept[ElabError]:
      class Top extends Module:
        val in = Slave(Handshake(UInt(8)))
        val out = Master(Flow(UInt(8)))
        in <> out
      new Top

    assert(error.getMessage.contains("shapes differ"), error.getMessage)
    assert(error.getMessage.contains("Handshake"), error.getMessage)
    assert(error.getMessage.contains("Flow"), error.getMessage)
