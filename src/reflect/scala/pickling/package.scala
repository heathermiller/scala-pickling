/*                     __                                               *\
**     ________ ___   / /  ___     Scala API                            **
**    / __/ __// _ | / /  / _ |    (c) 2003-2013, LAMP/EPFL             **
**  __\ \/ /__/ __ |/ /__/ __ |    http://scala-lang.org/               **
** /____/\___/_/ |_/____/_/ | |                                         **
**                          |/                                          **
\*                                                                      */

package scala

import java.lang.annotation.Inherited
import scala.annotation.MacroAnnotation
import scala.language.experimental.macros
import scala.reflect.runtime.{universe => ru}
import scala.reflect.api.Universe
import scala.reflect.macros.AnnotationMacro

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

  def genPicklerByType(c: Context)(tpe: c.Type): c.Tree = {
    import c.universe._
    import Flag._

    val irs = new IRs[c.universe.type](c.universe)
    import irs._

    val pickleFormatTree: Tree = c.inferImplicitValue(typeOf[PickleFormat]) match {
      case EmptyTree => c.abort(c.enclosingPosition, "Couldn't find implicit PickleFormat")
      case tree => tree
    }

    // get instance of PickleFormat
    val pickleFormatCarrier = c.typeCheck(Select(pickleFormatTree, TermName("instantiate")), silent = true)
    val pickleFormat = pickleFormatCarrier.attachments.all.find(_.isInstanceOf[PickleFormat]) match {
      case Some(pf: PickleFormat) => pf
      case _ => c.abort(c.enclosingPosition, s"Couldn't instantiate PickleFormat of type ${pickleFormatTree.tpe}")
    }

    // build IR
    debug("The tpe just before IR creation is: " + tpe)
    val oir = flatten(compose(ObjectIR(tpe, null, List())))
    val holes = oir.fields.map(fir => c.Expr[Pickle](Select(Select(Ident(TermName("obj")), TermName(fir.name)), TermName("pickle"))))
    val pickleLogic = pickleFormat.pickle(irs)(oir, holes)
    debug("Pickler.pickle = " + pickleLogic)

    def lookupRootClass(sym: Symbol): Symbol =
      if (sym.owner == NoSymbol || sym.owner == sym) sym
      else lookupRootClass(sym.owner)

    val rootClass = lookupRootClass(tpe.typeSymbol)

    def allKnownDirectSubclasses(sym: Symbol, in: Symbol): List[Symbol] = {
      val inType =
        if (in.isTerm) in.asTerm.typeSignature
        else in.asType.typeSignature

      val subclasses  = inType.members.filter(m => m.isClass &&
        m.asType.typeSignature.baseClasses.contains(sym)).toList

      val subpackages = inType.members.filter(_.isPackage).toList

      subclasses ++ subpackages.flatMap(pkg => allKnownDirectSubclasses(sym, pkg))
    }

    // now we also need to generate Pickler[S] for all subclasses S found above
    // can we call genPickler[S] somehow?
    // we need to generate a pickler based on a c.Type
    
    val sym = tpe.typeSymbol
    //println(s"knownDirectSubclasses of $sym:")
    //println(allKnownDirectSubclasses(sym, sym.owner))

    //val reifiedType = c.reifyType(Ident(newTermName("reflect.runtime.universe")), )
    //Select(treeBuild.mkRuntimeUniverseRef, TermName("rootMirror"))
    val reifiedTypeTree =
      c.reifyType(treeBuild.mkRuntimeUniverseRef, EmptyTree, tpe)

    val castAndAssignTree: c.Tree =
      ValDef(Modifiers(), "obj", TypeTree(tpe),
             TypeApply(Select(Ident("raw"), "asInstanceOf"), List(TypeTree(tpe))))

    /*
    val pickleBody: c.Expr[Pickle] = reify {
      c.Expr[Unit](castAndAssignTree).splice //akin to: val obj = raw.asInstanceOf[<tpe>]
      val rtpe: reflect.runtime.universe.Type = c.Expr[reflect.runtime.universe.TypeTag[T]](reifiedTypeTree).splice.tpe
      pickleLogic.splice
    }
    */

    val pickleBodyTree: Tree =
      Block(List(castAndAssignTree), pickleLogic.tree)

    val picklerTemplate = Template(
      List(AppliedTypeTree(Ident(newTypeName("Pickler")), List(TypeTree(tpe)))),
      emptyValDef,
      List(
        DefDef(
          Modifiers(),
          nme.CONSTRUCTOR,
          List(),
          List(List()),
          TypeTree(),
          Block(List(Apply(Select(Super(This(tpnme.EMPTY), tpnme.EMPTY), nme.CONSTRUCTOR), List())), Literal(Constant(())))
        ),
        DefDef(
          Modifiers(),
          TermName("pickle"),
          List(),
          List(List(ValDef(Modifiers(PARAM), newTermName("raw"), Ident(newTypeName("Any")), EmptyTree))),
          Ident(newTypeName("Pickle")),
          pickleBodyTree
        )
      )
    )

    Block(
      List(
        ValDef(Modifiers(IMPLICIT), newTermName("anon$pickler"), TypeTree(),
               Block(
                 List(ClassDef(Modifiers(FINAL), TypeName("$anon"), List(), picklerTemplate)),
                 Apply(Select(New(Ident(TypeName("$anon"))), nme.CONSTRUCTOR), List())
               )
             )
      ),
      Ident(TermName("anon$pickler"))
    )
  }

  def genPicklerImpl[T: c.WeakTypeTag](c: Context) =
    c.Expr[Pickler[T]](genPicklerByType(c)(c.universe.weakTypeOf[T]))
}

package pickling {

  trait Pickler[T] {
    def pickle(obj: Any): Pickle
    //def unpickle(p: Pickle): T
  }

  object Pickler {
    implicit def genPickler[T]: Pickler[T] = macro genPicklerImpl[T]
  }

  trait Pickle {
    val value: Any
  }

  @Inherited
  class pickleable extends MacroAnnotation {
    def transform = macro PickleableMacro.impl
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

  trait HasPicklerDispatch {
    def dispatchTo: Pickler[_]
  }

  trait PickleFormat {
    import ir._
    def instantiate = macro ???
    def pickle[U <: Universe with Singleton](irs: IRs[U])(ir: irs.ObjectIR, holes: List[irs.uni.Expr[Pickle]]): irs.uni.Expr[Pickle]
    // def unpickle[U <: Universe with Singleton](u: U)(pickle: u.Expr[Pickle]): u.Expr[(???.Type, Any)]
  }
}


