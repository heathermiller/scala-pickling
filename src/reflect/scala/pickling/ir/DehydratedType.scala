package scala.pickling
package ir

trait DehydratedType
case class DehydratedTypeRef(sym: String, args: List[DehydratedType]) extends DehydratedType

abstract class Dehydrator {
  val u: scala.reflect.api.Universe
  import u._

  def apply(tpe: Type): DehydratedType = tpe match {
    case TypeRef(_, sym, args) => DehydratedTypeRef(sym.fullName, args map apply)
    case _ => throw new MatchError(showRaw(tpe)) // more informative than the default MatchError
  }

  def unapply(dtpe: DehydratedType): Option[Type] = dtpe match {
    case DehydratedTypeRef(dsym, dargs) =>
      val sym = rootMirror.staticClass(dsym) // TODO: rootMirror here is dubious
      val args = dargs.map(unapply).map(_.get)
      Some(appliedType(sym.asType.toType, args))
  }
}