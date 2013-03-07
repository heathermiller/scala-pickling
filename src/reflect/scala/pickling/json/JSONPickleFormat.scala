package scala.pickling

package object json {
  import scala.reflect.api.Universe
  import ir._

  implicit object JSONPickleFormat extends PickleFormat {
    // def pickle[U <: Universe with Singleton](u: U)(ir: ObjectIR, holes: List[u.Expr[Pickle]]): u.Expr[Pickle] = {
    def pickle[U <: Universe with Singleton](u: U)(ir: ObjectIR, holes: List[u.Tree]): u.Tree = {
      import u._
      def genJsonAssembler() = {
        // TODO: using `obj` to refer to the value being pickled. seriously?!
        val tpe = hydrate(u)(ir.tpe)
        if (tpe =:= typeOf[Char] || tpe =:= typeOf[String]) q""" "\"" + obj.toString + "\"" """ // TODO: escape
        else if (tpe.typeSymbol.asClass.isPrimitive) q"obj.toString" // TODO: unit?
        else {
          def pickleTpe(dtpe: DehydratedType): Tree = {
            def loop(dtpe: DehydratedType): String = dtpe match {
              case DehydratedTypeRef(sym, Nil) => s"$sym"
              case DehydratedTypeRef(sym, targs) => s"$sym[${targs.map(targ => pickleTpe(targ))}]"
            }
            q""" "\"tpe\": \"" + ${loop(dtpe)} + "\"" """
          }
          def pickleField(name: String, hole: Tree): Tree = q""" "\"" + $name + "\": " + $hole.value """
          val fragmentTrees = pickleTpe(ir.tpe) +: (ir.fields.zip(holes).map{case (f, h) => pickleField(f.name, h)})
          val fragmentsTree = fragmentTrees.map(t => q""" "  " + $t """).reduce((t1, t2) => q""" $t1 + ",\n" + $t2 """)
          q""" "{\n" + $fragmentsTree + "\n}" """
        }
      }
      q"""new Pickle { val value = ${genJsonAssembler()} }"""
    }
    // def unpickle[U <: Universe with Singleton](u: U)(pickle: u.Expr[Pickle]): u.Expr[(DehydratedType, Any)] = ???
    def unpickle[U <: Universe with Singleton](u: U)(pickle: u.Tree): u.Tree = {
      import u._
      q"(??? : scala.pickling.ir.DehydratedType, ??? : Any)" // TODO: sigh, referential transparency of quasiquotes
    }
  }
}
