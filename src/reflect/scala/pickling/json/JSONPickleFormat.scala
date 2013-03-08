/*                     __                                               *\
**     ________ ___   / /  ___     Scala API                            **
**    / __/ __// _ | / /  / _ |    (c) 2003-2013, LAMP/EPFL             **
**  __\ \/ /__/ __ |/ /__/ __ |    http://scala-lang.org/               **
** /____/\___/_/ |_/____/_/ | |                                         **
**                          |/                                          **
\*                                                                      */

package scala.pickling

package object json {
  implicit val pickleFormat = new JSONPickleFormat
}

package json {
  import language.experimental.macros
  import scala.reflect.api.Universe
  import scala.reflect.macros.Context
  import ir._

  class JSONPickleFormat extends PickleFormat {
    override def instantiate = macro JSONPickleInstantiate.impl
    def pickle[U <: Universe with Singleton](irs: IRs[U])(ir: irs.ObjectIR, holes: List[irs.uni.Expr[Pickle]]): irs.uni.Expr[Pickle] = {
      import irs.uni._
      def genJsonAssembler() = {
        // TODO: using `obj` to refer to the value being pickled. needs a more robust approach
        val objTos = Expr[String](Select(Ident(TermName("obj")), TermName("toString")))
        val tpe = ir.tpe.typeSymbol.asType.toType
        if (tpe =:= typeOf[Char] || tpe =:= typeOf[String]) reify("\"" + objTos.splice + "\"") // TODO: escape
        else if (tpe.typeSymbol.asClass.isPrimitive) objTos // TODO: unit?
        else {
          def pickleTpe(tpe: Type): Expr[String] = {
            def loop(tpe: Type): String = tpe match {
              case TypeRef(_, sym, Nil) => s"${sym.fullName}"
              case TypeRef(_, sym, targs) => s"${sym.fullName}[${targs.map(targ => pickleTpe(targ))}]"
            }
            reify("\"tpe\": \"" + Expr[String](Literal(Constant(loop(tpe)))).splice + "\"")
          }
          def pickleField(name: String, hole: Expr[Pickle]) = reify("\"" + Expr[String](Literal(Constant(name))).splice + "\": " + hole.splice.value)
          val fragmentTrees = pickleTpe(tpe) +: (ir.fields.zip(holes).map{case (f, h) => pickleField(f.name, h)})
          val fragmentsTree = fragmentTrees.map(t => reify("  " + t.splice)).reduce((t1, t2) => reify(t1.splice + ",\n" + t2.splice))
          reify("{\n" + fragmentsTree.splice + "\n}")
        }
      }
      reify(new Pickle { val value = genJsonAssembler().splice })
    }
  }

  object JSONPickleInstantiate {
    def impl(c: Context) = c.universe.EmptyTree updateAttachment pickleFormat
  }
}


// "{ \"tpe\": \"" + pickleType(c)(tpe) + "\"\n" +
//           genFields(fields).splice(vals) + "\n" +
//           "}"
