package scala.pickling

import language.experimental.macros
import scala.reflect.macros.Macro

trait Pickler[T] {
  def pickle(raw: Any): Pickle
  def unpickle(p: Pickle): T
}

object Pickler {
  implicit def genPickler[T]: Pickler[T] = macro GenPicklerMacro.impl[T]
}

trait GenPicklerMacro extends Macro {
  import c.{universe => u}
  import c.universe._
  import ir._

  def impl[T: c.WeakTypeTag]: c.Expr[Pickler[T]] = {
    val pickleFormatTree: Tree = c.inferImplicitValue(typeOf[PickleFormat]) match {
      case EmptyTree => c.abort(c.enclosingPosition, "Couldn't find implicit PickleFormat")
      case tree => tree
    }
    val pickleFormat = c.eval(c.Expr[PickleFormat](c.resetAllAttrs(pickleFormatTree)))

    val T = weakTypeOf[T]
    debug("The tpe just before IR creation is: " + T)
    // TODO: unfortunately the algebraic IR thingie doesn't work because of dehydration
    val oir = ObjectIR(dehydrate(u)(T), null, T.baseClasses.map(_.typeSignature).flatMap(
      _.declarations.toList
       .filter(sym => !sym.isMethod && sym.isTerm && (sym.asTerm.isVar || sym.asTerm.isParamAccessor)) // separate issue: minimal versus verbose PickleFormat . i.e. someone might want all concrete inherited fields in their pickle
       .map(sym => FieldIR(sym.name.toString.trim, dehydrate(u)(sym.typeSignatureIn(T))))
       .toList))
    val holes = oir.fields.map(fir => c.Expr[Pickle](Select(Select(Ident(TermName("obj")), TermName(fir.name)), TermName("pickle"))))
    val pickleLogic = pickleFormat.pickle[u.type](u)(oir, holes)
    debug("Pickler.pickle = " + pickleLogic)
    val unpickleLogic = pickleFormat.unpickle[u.type](u)(c.Expr[Pickle](Ident(TermName("pickle"))))
    debug("Pickler.unpickle = " + unpickleLogic)

    reify {
      new Pickler[T] {
        def pickle(raw: Any): Pickle = {
          val obj = raw.asInstanceOf[T]
          pickleLogic.splice
        }
        def unpickle(p: Pickle): T = {
          val (dtpe, result) = unpickleLogic.splice
          // TODO: verify that dtpe <:< T
          result.asInstanceOf[T]
        }
      }
    }
  }
}