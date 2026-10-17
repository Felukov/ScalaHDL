// test/diag fixture — WIDTH_MISMATCH golden excerpt source. Not compiled;
// read by ExcerptSource.fromDir at test time. Positions match the spec R19
// block: count @ 11:7, threshold @ 12:7, assignment @ line 24, `+` @ col 16.
// The spec prints `--> 24:14` but the caret is under `+` at col 16; the
// renderer is caret-authoritative, so it reports `24:16`.
class Fifo extends Module:
  val clk = In(Bool())
  val rst = In(Bool())
  //
  //
      val count = In(UInt(8))
      val threshold = In(UInt(12))
  val sum = Out(UInt(12))
  //
  //
  //
  //
  //
  //
  //
  //
  //
  //
  sum := count + threshold
