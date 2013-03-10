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

trait PicklerMacros extends Macro {
  import c.universe._

  // a reify hack to make it statically work with the type which is calculated dynamically
  type CalculatedPickleType >: Pickle <: Pickle
  implicit def pickleTypeTrick: TypeTag[CalculatedPickleType] = TypeTag[CalculatedPickleType](pickleType)
  var pickleType: Type = null

  // another reify hack necessary because we cannot reify compound type trees with non-empty templates (SI-7235)
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
    // reify has a bug (SI-7235) which prevents it from working correctly with some CompoundTypeTrees
    // therefore here we have to do reification of that type by hand
    val corePicklerType = appliedType(typeOf[Pickler[Int]], List(tpe))
    val refinementOwner = build.newNestedSymbol(c.enclosingImpl.symbol, TypeName("<refinement>"), NoPosition, NoFlags, isClass = true)
    val refinedPickleType = build.newNestedSymbol(refinementOwner, TypeName("PickleType"), NoPosition, NoFlags, isClass = false)
    refinedPickleType.setTypeSignature(pickleType)
    picklerType = RefinedType(List(corePicklerType), newScopeWith(refinedPickleType), refinementOwner)
    refinementOwner.setTypeSignature(picklerType)

    // build pickler methods
    val pickleLogic = pickleFormatObj.pickle[c.universe.type, T](c.universe)(c.Expr[Any](Ident(TermName("pickleeT"))))
    debug("Pickler.pickle = " + pickleLogic)

    reify {
      implicit object anon$pickler extends Pickler[T] {
        type PickleType = CalculatedPickleType
        def pickle(picklee: Any): PickleType = {
          val pickleeT = picklee.asInstanceOf[T]
          pickleLogic.splice
        }
      }
      anon$pickler.asInstanceOf[CalculatedPicklerType]
    }.asInstanceOf[c.Expr[Pickler[T]]]
  }
}

trait UnpicklerMacros extends Macro {
  def impl[T: c.WeakTypeTag]: c.Expr[Unpickler[T]] = {
    import c.universe._
    import definitions._

    val tpe = weakTypeOf[T]
    val expectsValueIR = tpe.typeSymbol.asClass.isPrimitive || tpe.typeSymbol == StringClass
    val expectsObjectIR = !expectsValueIR

    def unexpectedIR: Expr[T] = {
      val irRef = c.Expr[UnpickleIR](Ident(TermName("ir")))
      val stpe = Expr[String](Literal(Constant(tpe.toString)))
      reify(throw new PicklingException(s"unexpected IR: ${irRef.splice} for type ${stpe.splice}"))
    }

    def unpickleValueIR: Expr[T] = {
      val vir = Expr[ValueIR](Ident(TermName("vir")))
      tpe match {
        case tpe if tpe =:= IntClass.toType => reify(vir.splice.value.asInstanceOf[Double].toInt.asInstanceOf[T])
        case tpe if tpe =:= StringClass.toType => reify(vir.splice.value.asInstanceOf[T])
        case _ => c.abort(c.enclosingPosition, "don't know how to unpickle a ValueIR as $tpe")
      }
    }

    def unpickleObjectIR: Expr[T] = {
      val oir = Expr[ObjectIR](Ident(TermName("oir")))
      val ctorSym = tpe.declaration(nme.CONSTRUCTOR).asMethod // TODO: multiple constructors
      val ctorTree = Select(New(TypeTree(tpe)), nme.CONSTRUCTOR)
      def ctorArg(name: Name, tpe: Type) = {
        val fieldIrTree = reify(oir.splice.fields(Expr[String](Literal(Constant(name.toString))).splice)).tree
        TypeApply(Select(fieldIrTree, "unpickle"), List(TypeTree(tpe)))
      }
      Expr[T](Apply(ctorTree, ctorSym.paramss.flatten.map(f => ctorArg(f.name, f.typeSignature)))) // TODO: multiple argument lists
    }

    reify {
      implicit object anon$unpickler extends Unpickler[T] {
        def unpickle(ir: UnpickleIR): T = ir match {
          case vir: ValueIR => (if (expectsValueIR) unpickleValueIR else unexpectedIR).splice
          case oir: ObjectIR => (if (expectsObjectIR) unpickleObjectIR else unexpectedIR).splice
          case _ => unexpectedIR.splice
        }
      }
      anon$unpickler.asInstanceOf[Unpickler[T]]
    }
  }
}

trait PickleMacros extends Macro {
  // NOTE: following my ponderings in flowdoc, this is the place which
  // will be dispatching picklees to correct picklers once we get to this
  // I mean, this will be the code generating stuff like:
  // (new HasPicklerDispatch {
  //   def dispatchTo: Pickler[_] = p match {
  //     case null => genPickler[Person]
  //     case _: Person => genPickler[Person]
  //     case _: Employee => genPickler[Employee]
  //     case _: Any => runtimeFallback
  //   }
  // }).dispatchTo.pickle(p)
  // as described at https://github.com/heathermiller/pickling-design-doc/blob/gh-pages/index.md
  def impl[T: c.WeakTypeTag](pickler: c.Expr[Pickler[T]]) = {
    import c.universe._
    val Apply(_, pickleeTree :: Nil) = c.prefix.tree
    c.universe.reify(pickler.splice.pickle(Expr[Any](pickleeTree).splice))
  }
}

trait UnpickleMacros extends Macro {
  def pickleUnpickle[T: c.WeakTypeTag]: c.Expr[T] = {
    import c.universe._
    val tpe = weakTypeOf[T]
    val pickleTree = c.prefix.tree

    // TODO: get rid of copy/paste w.r.t GenPicklerMacro
    def failUnpickle(msg: String) = c.abort(c.enclosingPosition, s"$msg for $pickleTree of type ${pickleTree.tpe}")
    val pickleFormatTypeCarrier = c.typeCheck(SelectFromTypeTree(TypeTree(pickleTree.tpe), TypeName("PickleFormatType")), mode = c.TYPEmode, silent = false)
    val pickleFormatTypeTree = pickleFormatTypeCarrier match {
      case EmptyTree => failUnpickle("Couldn't resolve PickleFormatType")
      case tree => tree.tpe.normalize match {
        case tpe if tpe.typeSymbol.isClass => tpe
        case tpe => failUnpickle(s"PickleFormatType resolved as $tpe is invalid")
      }
    }
    val pickleFormatTree = Apply(Select(New(TypeTree(pickleFormatTypeTree)), nme.CONSTRUCTOR), Nil)

    val currentClassloader = Select(Ident(TermName("getClass")), TermName("getClassLoader"))
    val currentMirror = Apply(Select(treeBuild.mkRuntimeUniverseRef, TermName("runtimeMirror")), List(currentClassloader))
    val reifiedTpe = TypeApply(Select(treeBuild.mkRuntimeUniverseRef, TermName("typeOf")), List(TypeTree(tpe)))
    val irTree = Apply(Select(pickleFormatTree, TermName("parse")), List(reifiedTpe, pickleTree, currentMirror))
    c.Expr[T](TypeApply(Select(irTree, TermName("unpickle")), List(TypeTree(tpe))))
  }

  def irUnpickle[T: c.WeakTypeTag]: c.Expr[T] = {
    import c.universe._
    val tpe = weakTypeOf[T]

    def unpickleAs(tpe: Type) = {
      val genPicklerTree = TypeApply(Select(Ident(typeOf[Unpickler.type].termSymbol), TermName("genUnpickler")), List(TypeTree(tpe)))
      Apply(Select(genPicklerTree, TermName("unpickle")), List(Ident(TermName("ir"))))
    }
    val valueIRUnpickleLogic = c.Expr[T](unpickleAs(tpe))
    def allSubclasses(tpe: Type): List[Type] = List(tpe) // TODO: implement this and share the logic with Heather & Philipp
    val defaultClause = CaseDef(Ident(nme.WILDCARD), EmptyTree, reify(throw new PicklingException("not yet implemented: runtime dispatch for unpickling")).tree)
    val subclassClauses = allSubclasses(tpe) map (tpe => {
      val reifiedTpe = TypeApply(Select(treeBuild.mkRuntimeUniverseRef, TermName("typeOf")), List(TypeTree(tpe)))
      val checkTpe = Apply(Select(Ident(TermName("tpe")), TermName("$eq$colon$eq")), List(reifiedTpe))
      CaseDef(Bind(TermName("tpe"), Ident(nme.WILDCARD)), checkTpe, unpickleAs(tpe))
    })
    // TODO: this should also go through HasPicklerDispatch, probably routed through a companion of T
    val objectIRUnpickleLogic = c.Expr[T](Match(Select(Ident(TermName("ir")), TermName("tpe")), subclassClauses :+ defaultClause))

    reify {
      val ir = c.prefix.splice
      ir match {
        case ir: ValueIR => valueIRUnpickleLogic.splice
        case ir: ObjectIR => objectIRUnpickleLogic.splice
        case ir => throw new PicklingException(s"unknown IR: $ir")
      }
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
