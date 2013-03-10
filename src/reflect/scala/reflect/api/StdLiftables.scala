package scala.reflect
package api

trait StdLiftables { self: Universe =>

  private def requireSameUniverse[T](universe: Universe, tp: String, value: T) =
    require(universe eq self, s"Can't lift $tp ${showRaw(value)} from universe ${showRaw(universe)} using lift$tp defined for ${showRaw(self)}.")

  implicit object liftExpr extends Liftable[Expr[_]] {
    def apply(universe: Universe, value: Expr[_]): universe.Tree = {
      requireSameUniverse(universe, "Expr", value)
      value.tree.asInstanceOf[universe.Tree]
    }
  }

  implicit object liftType extends Liftable[Type] {
    def apply(universe: Universe, value: Type): universe.Tree = {
      requireSameUniverse(universe, "Type", value)
      universe.TypeTree(value.asInstanceOf[universe.Type])
    }
  }

  implicit def liftTypeTag[T <: WeakTypeTag[_]]: Liftable[T] = new Liftable[T] {
    def apply(universe: Universe, value: T): universe.Tree = {
      requireSameUniverse(universe, "TypeTag", value)
      universe.TypeTree(value.asInstanceOf[universe.WeakTypeTag[_]].tpe)
    }
  }

  implicit def liftSymbol[T <: Symbol]:  Liftable[T] = new Liftable[T] {
    def apply(universe: Universe, value: T): universe.Tree = {
      requireSameUniverse(universe, "Symbol", value)
      universe.Ident(value.asInstanceOf[universe.Symbol])
    }
  }
}
