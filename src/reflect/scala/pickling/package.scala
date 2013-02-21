/*                     __                                               *\
**     ________ ___   / /  ___     Scala API                            **
**    / __/ __// _ | / /  / _ |    (c) 2003-2013, LAMP/EPFL             **
**  __\ \/ /__/ __ |/ /__/ __ |    http://scala-lang.org/               **
** /____/\___/_/ |_/____/_/ | |                                         **
**                          |/                                          **
\*                                                                      */

package scala

import scala.language.experimental.macros

package object pickling {

  import scala.reflect.macros.Context
  import ir._

  // TOGGLE DEBUGGING
  var debugEnabled: Boolean = System.getProperty("pickling.debug", "false").toBoolean
  def debug(output: => String) = if (debugEnabled) println(output)

  implicit class PickleOps[T](x: T) {
    def pickle(implicit pickler: Pickler[T], format: PickleFormat): Pickle = {
      pickler.pickle(x)
      //format.write(ir)
    }
  }

  implicit def genPickler[T]: Pickler[T] = macro genPicklerImpl[T]

  /*  This is terribly ugly, and I'd rather not have it, but it exists to get around a strange
   *  bug related to inferring the same implicit value more than once with inferImplicitValue.
   *
   *  However, this could still be useful, as it could probably help in some way in the future
   *  when dealing with recursive types.
   */
  class InferredInfo[C <: Context with Singleton](val ctx: C) {
    var alreadyInferredImplicits = List[(ctx.Type, ctx.Tree)]()
  }

  var ii: AnyRef = null

  def getOrInitInferredInfo[C <: Context with Singleton](ctx: C) = {
    if (ii == null)
      ii = new InferredInfo[ctx.type](ctx)
    ii.asInstanceOf[InferredInfo[ctx.type]]
  }

  def genPicklerImpl[T: c.WeakTypeTag](c: Context): c.Expr[Pickler[T]] = {
    import c.universe._
    val irs = new IRs[c.type](c)
    import irs._

    val tpe = weakTypeTag[T].tpe

    try {
      val pickleFormatTree = c.inferImplicitValue(typeOf[PickleFormat]) match {
        case EmptyTree => c.abort(c.enclosingPosition, "Couldn't find implicit PickleFormat")
        case tree => tree
      }

      // get instance of PickleFormat
      val pickleFormat = c.eval(c.Expr[PickleFormat](c.resetAllAttrs(pickleFormatTree)))

      // build IR
      debug("The tpe just before IR creation is: " + tpe)
      val oir = compose(ObjectIR(tpe, null, List()))

      debug("oir is: " + oir)
      debug("members(oir) is: " + members(oir))

      val localInferredInfo = getOrInitInferredInfo[c.type](c)

      val fieldIR2Pickler = members(oir).map { field =>
        // infer implicit pickler, if not found, try to generate pickler for field
        if (!localInferredInfo.alreadyInferredImplicits.exists(p => p._1 =:= field.tpe)) {
          debug("field.tpe before calling inferImplicitValue is: " + field.tpe)
          c.inferImplicitValue(
            typeRef(NoPrefix, typeOf[Pickler[_]].typeSymbol, List(field.tpe))
          ) match {
            case EmptyTree =>
              // EmptyTree essentially means that no pickler could be generated, so abort with error msg
              c.abort(c.enclosingPosition, "Couldn't generate implicit Pickler[" + field.tpe + "]")
            case tree =>
              localInferredInfo.alreadyInferredImplicits = (field.tpe -> tree) :: localInferredInfo.alreadyInferredImplicits
              field -> tree
          }
        } else {
          val Some((_, tree)) = localInferredInfo.alreadyInferredImplicits.find(p => p._1 =:= field.tpe)
          field -> tree
        }
      }.toMap
      debug("fieldIR2Pickler map: " + fieldIR2Pickler.toString)

      val (chunks: List[Any], holes: List[FieldIR]) = pickleFormat.genObjectTemplate(irs)(flatten(oir))
      debug("chunks: "+chunks.mkString("]["))
      debug("chunks.size:" + chunks.size)
      debug("holes.size:" + holes.size)

      // fill in holes
      def genFieldAccess(fir: FieldIR): Tree = {
        debug("selecting member [" + fir.name + "]")
        fieldIR2Pickler.get(fir) match {
          case None =>
            Select(Select(Select(Ident("obj"), fir.name), "pickle"), "value") // this is supposed to trigger implicit search and rexpansion
          case Some(picklerTree) =>
            Select(Apply(Select(picklerTree, "pickle"), List(Select(Ident("obj"), fir.name))), "value")
        }
      }

      def genChunkLiteral(chunk: Any): Tree =
        Literal(Constant(chunk))

      // assemble the pickle from the template
      val cs = chunks.init.zipWithIndex
      val pickleChunks: List[Tree] = for (c <- cs) yield {
        Apply(Select(pickleFormatTree, "concatChunked"), List(genChunkLiteral(c._1), genFieldAccess(holes(c._2))))
      }
      val concatAllTree = (pickleChunks :+ genChunkLiteral(chunks.last)) reduceLeft { (left: Tree, right: Tree) =>
        Apply(Select(pickleFormatTree, "concatChunked"), List(left, right))
      }

      val castAndAssignTree =
        ValDef(Modifiers(), "obj", TypeTree(tpe),
          TypeApply(Select(Ident("raw"), "asInstanceOf"), List(TypeTree(tpe)))
        )

      debug("Reifying pickler for tpe: " + tpe)

      // pass the assembled pickle into the generated runtime code
      val picklerExpr = reify {
        new Pickler[T] {
          def pickle(raw: Any): Pickle = {
            c.Expr[Unit](castAndAssignTree).splice //akin to: val obj = raw.asInstanceOf[<tpe>]
            new Pickle {
              val value = {
                c.Expr[Any](concatAllTree).splice
              }
            }
          }
        }
      }

      debug("The Expr that resulted from reify: " + picklerExpr)
      picklerExpr

    } catch {
      case t: Throwable => t.printStackTrace(); throw t
    }
  }
}

package pickling {
  import scala.reflect.macros.Context

  trait Pickler[T] {
    def pickle(obj: Any): Pickle
    //def unpickle(p: Pickle): T
  }

  trait Pickle {
    val value: Any
  }

  trait HasPicklerDispatch {
    def dispatchTo: Pickler[_]
  }

  // PickleFormat is intended to be used at compile time
  // to generate a pickle template which is to be inlined
  trait PickleFormat {
    import ir._

    def genTypeTemplate(c: Context)(tpe: c.universe.Type): Any
    def genObjectTemplate[C <: Context with Singleton](irs: IRs[C])(ir: irs.ObjectIR): (List[Any], List[irs.FieldIR])
    def genFieldTemplate[C <: Context with Singleton](irs: IRs[C])(ir: irs.FieldIR): (List[Any], List[irs.FieldIR])

    def concatChunked(c1: Any, c2: Any): Any
  }
}


