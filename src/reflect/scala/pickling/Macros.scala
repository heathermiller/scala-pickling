/*                     __                                               *\
**     ________ ___   / /  ___     Scala API                            **
**    / __/ __// _ | / /  / _ |    (c) 2003-2013, LAMP/EPFL             **
**  __\ \/ /__/ __ |/ /__/ __ |    http://scala-lang.org/               **
** /____/\___/_/ |_/____/_/ | |                                         **
**                          |/                                          **
\*                                                                      */

package scala.pickling

import scala.reflect.macros.AnnotationMacro
import scala.reflect.macros.Macro
import ir._

trait GenPicklerMacro extends Macro {
  import c.universe._
  val irs = new IRs[c.universe.type](c.universe)
  import irs._

  def impl[T: c.WeakTypeTag]: c.Expr[Pickler[T]] = {
    val tpe = weakTypeOf[T]

    // look up the implicit PickleFormat in scope
    val pickleFormatTree: Tree = c.inferImplicitValue(typeOf[PickleFormat]) match {
      case EmptyTree => c.abort(c.enclosingPosition, "Couldn't find implicit PickleFormat")
      case tree => tree
    }
    def failPickleFormat(msg: String) = c.abort(c.enclosingPosition, s"$msg for $pickleFormatTree of type ${pickleFormatTree.tpe}")

    // get instance of PickleFormat
    val pickleFormatCarrier = c.typeCheck(Select(pickleFormatTree, TermName("instantiate")), silent = true)
    val pickleFormat = pickleFormatCarrier.attachments.all.find(_.isInstanceOf[PickleFormat]) match {
      case Some(pf: PickleFormat) => pf
      case _ => failPickleFormat("Couldn't instantiate PickleFormat")
    }

    // build IR
    debug("The tpe just before IR creation is: " + tpe)
    val oir = flatten(compose(ObjectIR(tpe, null, List())))
    val holes = oir.fields.map(fir => c.Expr[Pickle](Select(Select(Ident(TermName("obj")), TermName(fir.name)), TermName("pickle"))))
    val pickleLogic = pickleFormat.pickle(irs)(oir, holes)
    debug("Pickler.pickle = " + pickleLogic)

    reify {
      implicit val anon$pickler = new Pickler[T] {
        def pickle(raw: Any): Pickle = {
          val obj = raw.asInstanceOf[T]
          pickleLogic.splice
        }
      }
      anon$pickler
    }
  }
}

trait PickleableMacro extends AnnotationMacro {
  def impl = {
    import c.universe._
    import Flag._
    c.annottee match {
      case ClassDef(mods, name, tparams, Template(parents, self, body)) =>
        // TODO: implement dispatchTo, add other stuff you find @pickleable should do
        val dispatchTo = DefDef(Modifiers(OVERRIDE), TermName("dispatchTo"), Nil, Nil, TypeTree(), Ident(TermName("$qmark$qmark$qmark")))
        ClassDef(mods, name, tparams, Template(parents, self, body :+ dispatchTo))
    }
  }
}
