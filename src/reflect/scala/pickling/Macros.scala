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
import scala.reflect.runtime.{universe => ru}
import ir._

trait GenPicklerMacro extends Macro {
  import c.universe._
  val irs = new IRs[c.universe.type](c.universe)
  import irs._

  // a reify hack to make it statically work with the type which is calculated dynamically
  type CalculatedPickleType >: Pickle <: Pickle
  implicit def pickleTypeTrick: TypeTag[CalculatedPickleType] = TypeTag[CalculatedPickleType](pickleType)
  var pickleType: Type = null

  // another reify hack necessary because we cannot reify compound type trees with non-empty templates
  type CalculatedPicklerType >: Pickler[_] <: Pickler[_]
  implicit def picklerTypeTrick: TypeTag[CalculatedPicklerType] = TypeTag[CalculatedPicklerType](picklerType)
  var picklerType: Type = null

  def impl[T: c.WeakTypeTag](pickleFormat: c.Expr[PickleFormat]): c.Expr[Pickler[T]] = {
    val tpe = weakTypeOf[T]

    // get instance of PickleFormat
    val pickleFormatTree = pickleFormat.tree
    def failPickleFormat(msg: String) = c.abort(c.enclosingPosition, s"$msg for $pickleFormatTree of type ${pickleFormatTree.tpe}")
    val pickleFormatCarrier = c.typeCheck(Select(pickleFormatTree, TermName("instantiate")), silent = true)
    val pickleFormatObj = pickleFormatCarrier.attachments.all.find(_.isInstanceOf[PickleFormat]) match {
      case Some(pf: PickleFormat) => pf
      case _ => failPickleFormat("Couldn't instantiate PickleFormat")
    }

    // get the type of pickles this format works with
    val pickleTypeCarrier = c.typeCheck(SelectFromTypeTree(TypeTree(pickleFormatTree.tpe), TypeName("PickleType")), mode = c.TYPEmode, silent = false)
    pickleType = pickleTypeCarrier match {
      case EmptyTree => failPickleFormat("Couldn't resolve PickleType")
      case tree => tree.tpe.normalize match {
        case tpe if tpe.typeSymbol.isClass => tpe
        case tpe => failPickleFormat(s"PickleType resolved as $tpe is invalid")
      }
    }

    // now calculate the type of the pickler, namely: Pickler[$T] { type PickleType = $pickleType }
    // reify has a bug which prevents it from working correctly with CompoundTypeTrees
    // therefore here we have to do reification of that type by hand
    val corePicklerType = appliedType(typeOf[Pickler[Int]], List(tpe))
    val refinementOwner = build.newNestedSymbol(c.enclosingImpl.symbol, TypeName("<refinement>"), NoPosition, NoFlags, isClass = true)
    val refinedPickleType = build.newNestedSymbol(refinementOwner, TypeName("PickleType"), NoPosition, NoFlags, isClass = false)
    refinedPickleType.setTypeSignature(pickleType)
    picklerType = RefinedType(List(corePicklerType), newScopeWith(refinedPickleType), refinementOwner)
    refinementOwner.setTypeSignature(picklerType)

    // build IR
    debug("The tpe just before IR creation is: " + tpe)
    val oir = flatten(compose(ObjectIR(tpe, null, List())))
    val holes = oir.fields.map(fir => c.Expr[pickleFormatObj.PickleType](Select(Select(Ident(TermName("obj")), TermName(fir.name)), TermName("pickle"))))
    val pickleLogic = pickleFormatObj.pickle(irs)(oir, holes)
    debug("Pickler.pickle = " + pickleLogic)

    reify {
      implicit object anon$pickler extends Pickler[T] {
        type PickleType = CalculatedPickleType
        def pickle(raw: Any): PickleType = {
          val obj = raw.asInstanceOf[T]
          pickleLogic.splice
        }
      }
      anon$pickler.asInstanceOf[CalculatedPicklerType]
    }.asInstanceOf[c.Expr[Pickler[T]]]
  }
}

trait PickleMacros extends Macro {
  def impl[T: c.WeakTypeTag](pickler: c.Expr[Pickler[T]]) = {
    import c.universe._
    val Apply(_, pickleeTree :: Nil) = c.prefix.tree
    c.universe.reify(pickler.splice.pickle(Expr[Any](pickleeTree).splice))
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
