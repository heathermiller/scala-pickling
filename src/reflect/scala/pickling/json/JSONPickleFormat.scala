package scala.pickling

package object json {
  import scala.reflect.api.Universe
  import ir._

  implicit object JSONPickleFormat extends PickleFormat {
    def pickle[U <: Universe with Singleton](u: U)(ir: ObjectIR, holes: List[u.Expr[Pickle]]): u.Expr[Pickle] = {
      import u._
      // TODO: boilerplate which is unfortunately necessary to upgrade trees to exprs
      case class FixedMirrorTreeCreator(mirror: scala.reflect.api.Mirror[U], tree: Tree) extends scala.reflect.api.TreeCreator {
        def apply[U <: Universe with Singleton](m: scala.reflect.api.Mirror[U]): U # Tree =
          if (m eq mirror) tree.asInstanceOf[U # Tree]
          else throw new IllegalArgumentException(s"Expr defined in $mirror cannot be migrated to other mirrors.")
      }
      def Expr[T: WeakTypeTag](tree: Tree): Expr[T] = u.Expr[T](u.rootMirror, FixedMirrorTreeCreator(u.rootMirror.asInstanceOf[scala.reflect.api.Mirror[U]], tree))
      def genJsonAssembler() = {
        // TODO: using `obj` to refer to the value being pickled. seriously?!
        val objTos = Expr[String](Select(Ident(TermName("obj")), TermName("toString")))
        val tpe = hydrate(u)(ir.tpe)
        if (tpe =:= typeOf[Char] || tpe =:= typeOf[String]) reify("\"" + objTos.splice + "\"") // TODO: escape
        else if (tpe.typeSymbol.asClass.isPrimitive) objTos // TODO: unit?
        else {
          def pickleTpe(dtpe: DehydratedType): Expr[String] = {
            def loop(dtpe: DehydratedType): String = dtpe match {
              case DehydratedTypeRef(sym, Nil) => s"$sym"
              case DehydratedTypeRef(sym, targs) => s"$sym[${targs.map(targ => pickleTpe(targ))}]"
            }
            reify("\"tpe\": \"" + Expr[String](Literal(Constant(loop(dtpe)))).splice + "\"")
          }
          def pickleField(name: String, hole: Expr[Pickle]) = reify("\"" + Expr[String](Literal(Constant(name))).splice + "\": " + hole.splice.value)
          val fragmentTrees = pickleTpe(ir.tpe) +: (ir.fields.zip(holes).map{case (f, h) => pickleField(f.name, h)})
          val fragmentsTree = fragmentTrees.map(t => reify("  " + t.splice)).reduce((t1, t2) => reify(t1.splice + ",\n" + t2.splice))
          reify("{\n" + fragmentsTree.splice + "\n}")
        }
      }
      reify(new Pickle { val value = genJsonAssembler().splice })
    }
    def unpickle[U <: Universe with Singleton](u: U)(pickle: u.Expr[Pickle]): u.Expr[(DehydratedType, Any)] = {
      import u._
      reify((??? : DehydratedType, ??? : Any))
    }
  }
}
