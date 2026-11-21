package scalahdl.interface

import scalahdl.core.{Direction, Type}
import scalahdl.dsl.{*, given}
import scalahdl.elab.Module
import scalahdl.elab.Builder.ElabError
import scalahdl.emit.VerilogEmitter

/** R31 — a user-defined interface: written from the master's side, declared
  * as a master or as a slave, nested with its role composed, and connected
  * leaf by leaf from the side the module reads to the side it drives. */
final class InterfaceSuite extends munit.FunSuite:

  private def emit(top: Module): String =
    new VerilogEmitter("0.1.2").emitModule(top.elaborateDesign().top, "Top")

  private def ports(top: Module): List[(String, Direction, Type)] =
    top.elaborateDesign().top.ports.map(p => (p.name, p.direction, p.typeOf))

  final case class Word() extends Bundle:
    val addr = UInt(25)
    val write = Bool()

  final case class Answer() extends Bundle:
    val valid = Bool()
    val data = UInt(16)

  private val wordType = Type.Bundle(List("addr" -> Type.UInt(25), "write" -> Type.Bool))
  private val answerType = Type.Bundle(List("valid" -> Type.Bool, "data" -> Type.UInt(16)))

  /** A bus cycle, written from the processor's side: it presents a request
    * and is answered by a level and a word. */
  final case class Cycle() extends Interface:
    val req_valid = Out(Bool())
    val req = Out(Word())
    val ack = In(Bool())
    val data = In(UInt(16))

  /** A memory's client, written from the client's side: a handshake out and
    * an answer that cannot be refused back. */
  final case class Client() extends Interface:
    val req = Master(Handshake(Word()))
    val rsp = In(Answer())

  // ---- declaration

  test("a master declares one port per leaf, named `<endpoint>_<field>`, in order"):
    class Top extends Module:
      val cpu = Master(Cycle())
      cpu.req_valid := True
      cpu.req.addr := 0.U(25)
      cpu.req.write := False

    assertEquals(ports(new Top), List(
      ("cpu_req_valid", Direction.Out, Type.Bool),
      ("cpu_req", Direction.Out, wordType),
      ("cpu_ack", Direction.In, Type.Bool),
      ("cpu_data", Direction.In, Type.UInt(16))))

  test("a slave is the mirror image"):
    class Top extends Module:
      val cpu = Slave(Cycle())
      cpu.ack := cpu.req_valid
      cpu.data := Mux(cpu.req.write, 0.U(16), 1.U(16))

    assertEquals(ports(new Top), List(
      ("cpu_req_valid", Direction.In, Type.Bool),
      ("cpu_req", Direction.In, wordType),
      ("cpu_ack", Direction.Out, Type.Bool),
      ("cpu_data", Direction.Out, Type.UInt(16))))

  test("a nested interface composes its role with the endpoint's"):
    class AClient extends Module:
      val mem = Master(Client())
      mem.req.valid := True
      mem.req.payload.addr := 1.U(25)
      mem.req.payload.write := False

    assertEquals(ports(new AClient), List(
      ("mem_req_valid", Direction.Out, Type.Bool),
      ("mem_req_ready", Direction.In, Type.Bool),
      ("mem_req_payload", Direction.Out, wordType),
      ("mem_rsp", Direction.In, answerType)))

    class AMemory extends Module:
      val client = Slave(Client())
      client.req.ready := True
      client.rsp.valid := client.req.valid
      client.rsp.data := 0.U(16)

    assertEquals(ports(new AMemory), List(
      ("client_req_valid", Direction.In, Type.Bool),
      ("client_req_ready", Direction.Out, Type.Bool),
      ("client_req_payload", Direction.In, wordType),
      ("client_rsp", Direction.Out, answerType)))

  test("a slave's slave reads as written"):
    final case class Inner() extends Interface:
      val go = Out(Bool())
    final case class Outer() extends Interface:
      val back = Slave(Inner())
    class Top extends Module:
      val x = Slave(Outer())
      x.back.go := True

    assertEquals(ports(new Top), List(("x_back_go", Direction.Out, Type.Bool)))

  test("a field's val is the port: flattening is R07.1's, access is typed"):
    class Top extends Module:
      val mem = Master(Client())
      val word = Out(UInt(16))
      mem.req.valid := mem.rsp.valid
      mem.req.payload.addr := 2.U(25)
      mem.req.payload.write := mem.req.ready
      word := mem.rsp.data

    val v = emit(new Top)
    assert(v.contains("mem_req_payload_addr"), v)
    assert(v.contains("mem_req_payload_write"), v)
    assert(v.contains("mem_rsp_valid"), v)
    assert(v.contains("word = mem_rsp_data;"), v)

  test("a Vec of bundles is a field like any other"):
    final case class Lanes() extends Interface:
      val lanes = Out(Vec(2, Word()))
      val ok = In(Bool())
    class Top extends Module:
      val l = Master(Lanes())
      l.lanes(0).addr := 0.U(25)
      l.lanes(0).write := l.ok
      l.lanes(1).addr := 1.U(25)
      l.lanes(1).write := False

    val v = emit(new Top)
    assert(v.contains("l_lanes_1_addr"), v)
    assert(v.contains("l_lanes_0_write = l_ok;"), v)

  object Op extends Encoding(2):
    val Nop, Load, Store = Value

  test("an encoding is a field like any other, and the signal carries it"):
    final case class Command() extends Interface:
      val op = Out(Op())
      val done = In(Bool())
    class Top extends Module:
      val c = Master(Command())
      c.op := Mux(c.done, Op.Nop, Op.Load)

    val top = (new Top).elaborateDesign().top
    assertEquals(top.ports.map(p => (p.name, p.direction, p.typeOf)), List(
      ("c_op", Direction.Out, Type.UInt(2)),
      ("c_done", Direction.In, Type.Bool)))
    assert(top.signals.find(_.name == "c_op").exists(_.encoding.isDefined))

  // ---- connection

  class Producer extends Module:
    val out = Master(Handshake(UInt(8)))
    out.valid := True
    out.payload := 7.U(8)

  class Consumer extends Module:
    val in = Slave(Handshake(UInt(8)))
    val out = Out(UInt(8))
    in.ready := True
    out := in.payload

  test("`<>` inside one module passes a slave through to a master"):
    class Top extends Module:
      val s = Slave(Handshake(UInt(8)))
      val m = Master(Handshake(UInt(8)))
      s <> m

    val top = (new Top).elaborateDesign().top
    assertEquals(top.drivers.size, 3)
    val v = new VerilogEmitter("0.1.2").emitModule(top, "Top")
    assert(v.contains("m_valid = s_valid;"), v)
    assert(v.contains("m_payload = s_payload;"), v)
    assert(v.contains("s_ready = m_ready;"), v)

  test("`<>` between two instances connects the pins and adds no driver"):
    class Top extends Module:
      given ClockDomain = scalahdl.clock.ClockDomain.noClock
      val producer = inst(new Producer)
      val consumer = inst(new Consumer)
      val y = Out(UInt(8))
      producer.out <> consumer.in
      y := consumer.out

    val top = (new Top).elaborateDesign().top
    assertEquals(top.drivers.size, 1)
    assertEquals(top.instances.map(i => i.name -> i.connections.map(_._1)), List(
      "producer" -> List("out_valid", "out_ready", "out_payload"),
      "consumer" -> List("in_valid", "in_ready", "in_payload", "out")))

  test("`<>` forwards an own slave to a child's slave"):
    class Top extends Module:
      given ClockDomain = scalahdl.clock.ClockDomain.noClock
      val in = Slave(Handshake(UInt(8)))
      val y = Out(UInt(8))
      val consumer = inst(new Consumer)
      in <> consumer.in
      y := consumer.out

    val v = emit(new Top)
    assert(v.contains(".in_valid(in_valid)"), v)
    assert(v.contains(".in_payload(in_payload)"), v)
    assert(v.contains("assign in_ready = consumer_in_ready;"), v)

  test("`<>` forwards a child's master to an own master"):
    class Top extends Module:
      given ClockDomain = scalahdl.clock.ClockDomain.noClock
      val out = Master(Handshake(UInt(8)))
      val producer = inst(new Producer)
      producer.out <> out

    val v = emit(new Top)
    assert(v.contains("assign out_valid = producer_out_valid;"), v)
    assert(v.contains("assign out_payload = producer_out_payload;"), v)
    assert(v.contains(".out_ready(out_ready)"), v)

  test("`<>` forwards a nested interface with its bundle leaves"):
    class AMemory extends Module:
      val client = Slave(Client())
      client.req.ready := True
      client.rsp.valid := client.req.valid
      client.rsp.data := client.req.payload.addr(15, 0)

    class Top extends Module:
      given ClockDomain = scalahdl.clock.ClockDomain.noClock
      val client = Slave(Client())
      val memory = inst(new AMemory)
      client <> memory.client

    val v = emit(new Top)
    assert(v.contains(".client_req_valid(client_req_valid)"), v)
    assert(v.contains(".client_req_payload_addr(client_req_payload_addr)"), v)
    assert(v.contains(".client_req_payload_write(client_req_payload_write)"), v)
    assert(v.contains("assign client_req_ready = memory_client_req_ready;"), v)
    assert(v.contains("assign client_rsp_valid = memory_client_rsp_valid;"), v)
    assert(v.contains("assign client_rsp_data = memory_client_rsp_data;"), v)

  // ---- what is refused

  test("two masters leave the data with no driver"):
    val error = intercept[ElabError]:
      class Top extends Module:
        given ClockDomain = scalahdl.clock.ClockDomain.noClock
        val left = inst(new Producer)
        val right = inst(new Producer)
        left.out <> right.out
      new Top

    assert(error.getMessage.contains("both read here"), error.getMessage)
    assert(error.getMessage.contains("`out_valid`"), error.getMessage)

  test("two own masters leave the data with two drivers"):
    val error = intercept[ElabError]:
      class Top extends Module:
        val a = Master(Handshake(UInt(8)))
        val b = Master(Handshake(UInt(8)))
        a <> b
      new Top

    assert(error.getMessage.contains("both driven here"), error.getMessage)
    assert(error.getMessage.contains("`a_valid`"), error.getMessage)
    assert(error.getMessage.contains("`b_valid`"), error.getMessage)

  test("two shapes that differ do not connect, and the message shows both"):
    val error = intercept[ElabError]:
      class Top extends Module:
        val cpu = Slave(Cycle())
        val mem = Master(Client())
        cpu <> mem
      new Top

    assert(error.getMessage.contains("shapes differ"), error.getMessage)
    assert(error.getMessage.contains("Cycle"), error.getMessage)
    assert(error.getMessage.contains("Client"), error.getMessage)
    assert(error.getMessage.contains("req: Master [valid: Out Bool"), error.getMessage)

  test("an endpoint of a module that was never instantiated is refused"):
    val error = intercept[ElabError]:
      class Top extends Module:
        given ClockDomain = scalahdl.clock.ClockDomain.noClock
        val consumer = inst(new Consumer)
        val stray = new Producer
        stray.out <> consumer.in
      new Top

    assert(error.getMessage.contains("not instantiated here"), error.getMessage)

  test("an endpoint must be bound to a val"):
    val error = intercept[ElabError]:
      class Top extends Module:
        Master(Cycle())
      new Top

    assert(error.getMessage.contains("anonymous endpoint"), error.getMessage)

  test("one interface instance is one endpoint"):
    val error = intercept[ElabError]:
      class Top extends Module:
        val shared = Cycle()
        val a = Master(shared)
        val b = Slave(shared)
      new Top

    assert(error.getMessage.contains("already declared as `a`"), error.getMessage)

  // ---- extension

  /** The transfer half of a bus: what every device on it is wired to. */
  class Transfer extends Interface:
    val strobe = Out(Bool())
    val addr = Out(UInt(23))
    val ack = In(Bool())

  object Transfer:
    def apply(): Transfer = new Transfer

  /** ...and the whole bus, which is that plus the answers only some devices
    * give. Written as a subclass so the shared half is declared once. */
  final case class Bus() extends Transfer:
    val retry = In(Bool())
    val space = Out(UInt(3))

  test("an interface may extend another: the base's fields come first, flat"):
    class Top extends Module:
      val a = Master(Transfer())
      val b = Master(Bus())
      a.strobe := True
      a.addr := 0.U(23)
      b.strobe := False
      b.addr := 0.U(23)
      b.space := 0.U(3)

    assertEquals(ports(new Top), List(
      ("a_strobe", Direction.Out, Type.Bool),
      ("a_addr", Direction.Out, Type.UInt(23)),
      ("a_ack", Direction.In, Type.Bool),
      ("b_strobe", Direction.Out, Type.Bool),
      ("b_addr", Direction.Out, Type.UInt(23)),
      ("b_ack", Direction.In, Type.Bool),
      ("b_retry", Direction.In, Type.Bool),
      ("b_space", Direction.Out, Type.UInt(3))))

  class Device extends Module:
    val bus = Slave(Bus())
    bus.ack := bus.strobe
    bus.retry := False

  test("a subclass is a shape of its own: the base's is a prefix, not a match"):
    val error = intercept[ElabError]:
      class Top extends Module:
        given scalahdl.clock.ClockDomain = scalahdl.clock.ClockDomain.noClock
        val own = Slave(Transfer())
        val u = inst(new Device)
        own <> u.bus
      new Top

    assert(error.getMessage.contains("shapes differ"), error.getMessage)

  // ---- a Vec of endpoints

  /** A module that faces several clients at once, which is what a `Vec` of
    * endpoints is for. */
  class Fanout extends Module:
    val client = Slave(Vec(2, Client()))
    client.foreach { c =>
      c.req.ready := True
      c.rsp.valid := c.req.valid
      c.rsp.data := 0.U(16)
    }

  test("a Vec of interfaces is n endpoints, named `<endpoint>_<index>_<field>`"):
    assertEquals(ports(new Fanout), List(
      ("client_0_req_valid", Direction.In, Type.Bool),
      ("client_0_req_ready", Direction.Out, Type.Bool),
      ("client_0_req_payload", Direction.In, wordType),
      ("client_0_rsp", Direction.Out, answerType),
      ("client_1_req_valid", Direction.In, Type.Bool),
      ("client_1_req_ready", Direction.Out, Type.Bool),
      ("client_1_req_payload", Direction.In, wordType),
      ("client_1_rsp", Direction.Out, answerType)))

  test("one element connects like any other endpoint"):
    class Top extends Module:
      given scalahdl.clock.ClockDomain = scalahdl.clock.ClockDomain.noClock
      val pass = Slave(Client())
      val u = inst(new Fanout)
      pass <> u.client(0)
      u.client(1).req.valid := False
      u.client(1).req.payload.addr := 0.U(25)
      u.client(1).req.payload.write := False

    val v = emit(new Top)
    // What this module reads goes straight onto the child's pin...
    assert(v.contains(".client_0_req_valid(pass_req_valid)"), v)
    assert(v.contains(".client_0_req_payload_addr(pass_req_payload_addr)"), v)
    // ...and what the child drives comes back through a wire, as any child's
    // output does. Element 1 is untouched by the connection.
    assert(v.contains("assign pass_req_ready = u_client_0_req_ready;"), v)
    assert(v.contains("assign pass_rsp_valid = u_client_0_rsp_valid;"), v)
    assert(v.contains("assign pass_rsp_data = u_client_0_rsp_data;"), v)
    assert(v.contains(".client_1_req_valid(1'b0)"), v)

  test("each element is its own interface: one instance cannot be n endpoints"):
    val error = intercept[ElabError]:
      class Top extends Module:
        val shared = Client()
        val client = Slave(Vec(2, shared))
      new Top

    assert(error.getMessage.contains("already declared as `client_0`"), error.getMessage)

  // ---- a Vec of interfaces as a nested field

  /** One channel of a bank: a word and a strobe. */
  final case class Lane() extends Interface:
    val word = Out(Word())
    val strobe = In(Bool())

  /** A bank whose channels are a nested `Vec` rather than one field each. */
  final case class Bank() extends Interface:
    val lane = Slave(Vec(2, Lane()))
    val ready = Out(Bool())

  final class BankOf(v: InterfaceVec[Lane]) extends Interface:
    val lane = Slave(v)

  test("a nested Vec is n nested fields, named `<field>_<index>`"):
    class Top extends Module:
      val bank = Master(Bank())
      bank.lane(0).strobe := True
      bank.lane(1).strobe := False
      bank.ready := True

    assertEquals(ports(new Top), List(
      ("bank_lane_0_word", Direction.In, wordType),
      ("bank_lane_0_strobe", Direction.Out, Type.Bool),
      ("bank_lane_1_word", Direction.In, wordType),
      ("bank_lane_1_strobe", Direction.Out, Type.Bool),
      ("bank_ready", Direction.Out, Type.Bool)))

  test("a nested Vec connects leaf by leaf, like any other nested endpoint"):
    class Top extends Module:
      val s = Slave(Bank())
      val m = Master(Bank())
      s <> m

    val v = emit(new Top)
    assert(v.contains("s_lane_0_word_addr = m_lane_0_word_addr;"), v)
    assert(v.contains("m_lane_0_strobe = s_lane_0_strobe;"), v)
    assert(v.contains("s_lane_1_word_write = m_lane_1_word_write;"), v)
    assert(v.contains("m_ready = s_ready;"), v)

  test("a nested Vec keeps the fresh-element rule: one instance cannot be n fields"):
    val error = intercept[ElabError]:
      class Top extends Module:
        val shared = Lane()
        val bank = Master(BankOf(Vec(2, shared)))
      new Top

    assert(error.getMessage.contains("already declared as `bank_lane_0`"), error.getMessage)

  test("an interface that is not an endpoint cannot be connected"):
    val error = intercept[ElabError]:
      class Top extends Module:
        val cpu = Slave(Cycle())
        cpu <> Cycle()
      new Top

    assert(error.getMessage.contains("not declared as an endpoint"), error.getMessage)
