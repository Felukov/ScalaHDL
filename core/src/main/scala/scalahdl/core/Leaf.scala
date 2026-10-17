package scalahdl.core

/** R07.1 / R14.1 — one step down into an aggregate: a bundle field, or a
  * constant `Vec` index.
  *
  * The IR preserves aggregates (R17) and resolves them to leaves at write
  * time, so more than one reader needs the same walk: R11's completeness is a
  * leaf property (a write to one field cannot cover its siblings), and R28's
  * dependency graph is leaf-granular for the same reason one level along (a
  * bundle wire whose one field is computed from another is not a cycle). This
  * is that walk, in one place, so the two agree on what a leaf is. */
enum LeafSeg:
  case Field(name: String)
  case Index(value: Int)

object Leaf:

  /** Every leaf path of a type, in declaration order. A scalar has exactly one
    * leaf — the empty path — which is what makes a scalar and an aggregate the
    * same case for every caller. */
  def leaves(t: Type): List[List[LeafSeg]] = t match
    case Type.Bundle(fields) => fields.flatMap { case (name, fieldType) =>
      leaves(fieldType).map(LeafSeg.Field(name) :: _)
    }
    case Type.Vec(n, elem) => (0 until n).toList.flatMap { index =>
      leaves(elem).map(LeafSeg.Index(index) :: _)
    }
    case _ => List(Nil)

  /** The type reached by following `path` into `t`. A path that does not fit
    * the type yields the type it stopped at rather than throwing: callers are
    * analyses, and a malformed path is a defect the type checker already
    * reports elsewhere. */
  def typeAt(t: Type, path: List[LeafSeg]): Type = (t, path) match
    case (_, Nil) => t
    case (Type.Bundle(fields), LeafSeg.Field(name) :: rest) =>
      fields.find(_._1 == name).map(field => typeAt(field._2, rest)).getOrElse(t)
    case (Type.Vec(_, elem), LeafSeg.Index(_) :: rest) => typeAt(elem, rest)
    case _ => t

  /** The root signal and the constant path of an assignment target.
    *
    * `None` where the path is not constant — a runtime `Vec` index (R14.2) or
    * a part-select window (R6.7). Both require a register, so on a
    * combinational target `None` is unreachable rather than merely unhandled;
    * it is the answer that stays right if that ever changes. */
  def path(lhs: Lhs): Option[(SignalId, List[LeafSeg])] = lhs match
    case Lhs.Sig(id) => Some(id -> Nil)
    case Lhs.Field(base, name) => path(base).map { case (id, p) =>
      id -> (p :+ LeafSeg.Field(name))
    }
    case Lhs.Index(base, Expr.Lit(value, _, _)) => path(base).map { case (id, p) =>
      id -> (p :+ LeafSeg.Index(value.toInt))
    }
    case Lhs.Index(_, _) => None
    case Lhs.Slice(_, _, _) => None
    case Lhs.VarSlice(_, _, _) => None

  /** The same resolution on the reading side: the leaf a selection chain names.
    * `Slice` keeps the path it stood on — a window of a scalar is that scalar
    * — and a runtime index does not resolve, because it names every element. */
  def readPath(e: Expr): Option[(SignalId, List[LeafSeg])] = e match
    case Expr.Ref(id) => Some(id -> Nil)
    case Expr.Field(base, name) => readPath(base).map { case (id, p) =>
      id -> (p :+ LeafSeg.Field(name))
    }
    case Expr.Index(base, Expr.Lit(value, _, _)) => readPath(base).map { case (id, p) =>
      id -> (p :+ LeafSeg.Index(value.toInt))
    }
    case Expr.Slice(base, _, _) => readPath(base)
    // R34 — a runtime-offset window reads a run of bits of its base, so the
    // leaf it stands on is the base's, exactly as `Slice` does.
    case Expr.VarSlice(base, _, _) => readPath(base)
    case _ => None

  /** `.field(3)` — the suffix a diagnostic appends to the signal's name. */
  def render(path: List[LeafSeg]): String = path.map {
    case LeafSeg.Field(name)  => s".$name"
    case LeafSeg.Index(value) => s"($value)"
  }.mkString
