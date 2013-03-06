package scala.pickling

import scala.reflect.api.Universe
import ir._

trait PickleFormat {
  def pickle[U <: Universe with Singleton](u: U)(ir: ObjectIR, holes: u.Expr[Pickle]*): u.Expr[Pickle]
  def unpickle[U <: Universe with Singleton](u: U)(pickle: u.Expr[Pickle]): u.Expr[(DehydratedType, Any)]
}
