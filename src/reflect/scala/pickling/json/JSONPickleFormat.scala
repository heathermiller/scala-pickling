package scala.pickling

package object json {
  import scala.reflect.api.Universe
  import ir._

  implicit object JSONPickleFormat extends PickleFormat {
    def pickle[U <: Universe with Singleton](u: U)(ir: ObjectIR, holes: u.Expr[Pickle]*): u.Expr[Pickle] = ???
    def unpickle[U <: Universe with Singleton](u: U)(pickle: u.Expr[Pickle]): u.Expr[(DehydratedType, Any)] = ???
  }
}
