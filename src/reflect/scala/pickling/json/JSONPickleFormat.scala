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
  import scala.util.parsing.json._
  import ir._

  case class JSONPickle(val value: String) extends Pickle {
    type ValueType = String
  }

  class JSONPickleFormat extends PickleFormat {
    type PickleType = JSONPickle
    override def instantiate = macro JSONPickleInstantiate.impl
    def pickle[U <: Universe with Singleton, T: u.WeakTypeTag](u: Universe)(picklee: u.Expr[Any]): u.Expr[JSONPickle] = {
      import u._
      import definitions._
      val tpe = weakTypeOf[T] // TODO: do we enforce T being non-weak?
      val sym = tpe.typeSymbol.asClass

      def reifyRuntimeClass(tpe: Type): Tree = tpe.normalize match {
        case TypeRef(_, arrayClass, componentTpe :: Nil) if arrayClass == ArrayClass =>
          val componentErasure = reifyRuntimeClass(componentTpe)
          val ScalaRunTimeModule = rootMirror.staticModule("scala.runtime.ScalaRunTime")
          Apply(Select(Ident(ScalaRunTimeModule), TermName("arrayClass")), List(componentErasure))
        case _ =>
          TypeApply(Select(Ident(PredefModule), TermName("classOf")), List(TypeTree(tpe.erasure)))
      }
      val rtpe = Expr[Class[_]](reifyRuntimeClass(tpe))

      val irs = new IRs[u.type](u)
      import irs._
      val oir = flatten(compose(ObjectIR(tpe, null, List())))

      val rawJsonAssembly: Expr[String] = {
        // TODO: so we don't reify type info for primitives?
        if (sym == CharClass || sym == StringClass) reify("\"" + JSONFormat.quoteString(picklee.splice.toString) + "\"")
        else if (sym.isPrimitive) reify(picklee.splice.toString) // TODO: unit?
        else {
          def pickleTpe(tpe: Type): Expr[String] = {
            def loop(tpe: Type): String = tpe match {
              case TypeRef(_, sym, Nil) => s"${sym.fullName}"
              case TypeRef(_, sym, targs) => s"${sym.fullName}[${targs.map(targ => pickleTpe(targ))}]"
            }
            reify("\"tpe\": \"" + Expr[String](Literal(Constant(loop(tpe)))).splice + "\"")
          }
          def fieldPickle(name: String) = Expr[JSONPickle](Select(Select(picklee.tree, TermName(name)), TermName("pickle")))
          def pickleField(name: String) = reify("\"" + Expr[String](Literal(Constant(name))).splice + "\": " + fieldPickle(name).splice.value)
          val fragmentTrees = pickleTpe(tpe) +: oir.fields.map(f => pickleField(f.name))
          val fragmentsTree = fragmentTrees.map(t => reify("  " + t.splice)).reduce((t1, t2) => reify(t1.splice + ",\n" + t2.splice))
          reify("{\n" + fragmentsTree.splice + "\n}")
        }
      }
      val nullSafeJsonAssembly: Expr[String] = reify(if (picklee.splice ne null) rawJsonAssembly.splice else "null")
      val ensuringNoDerivedClassesJsonAssembly: Expr[String] = reify {
        if ((picklee.splice ne null) && picklee.splice.getClass != rtpe.splice)
          throw new PicklingException(s"Fatal: unexpected input of type ${picklee.splice.getClass} in Pickler[${rtpe.splice}]")
        nullSafeJsonAssembly.splice
      }
      val jsonAssembly: Expr[String] =
        if (sym.isPrimitive || sym.isDerivedValueClass) rawJsonAssembly
        else if (sym.isFinal) nullSafeJsonAssembly
        else ensuringNoDerivedClassesJsonAssembly

      reify(JSONPickle(jsonAssembly.splice))
    }
  }

  object JSONPickleInstantiate {
    def impl(c: Context) = c.universe.EmptyTree updateAttachment pickleFormat
  }
}
