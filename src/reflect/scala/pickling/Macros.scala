package scala.pickling

import scala.reflect.macros.AnnotationMacro
import scala.reflect.runtime.{universe => ru}
import ir._

trait PicklerMacros extends Macro {
  def impl[T: c.WeakTypeTag](pickleFormat: c.Tree): c.Tree = {
    import c.universe._
    import definitions._
    val tpe = weakTypeOf[T]
    val sym = tpe.typeSymbol.asClass

    def pickleAs(tpe: Type) = {
      if (tpe.typeSymbol.asClass.typeParams.nonEmpty)
        c.abort(c.enclosingPosition, s"implementation restriction: cannot pickle polymorphic type $tpe")
      import irs._
      val cir = flatten(compose(ClassIR(tpe, null, List())))
      val fieldAccessor = (fir: FieldIR) => {
        if (!fir.isPublic) c.abort(c.enclosingPosition, s"implementation restriction: cannot pickle non-public field ${fir.name} in class $tpe")
        Expr[Pickle](q"picklee.${TermName(fir.name)}.pickle")
      }
      q"""
        val picklee = pickleeRaw.asInstanceOf[$tpe]
        ${instantiatePickleFormat(pickleFormat).formatCT[c.universe.type](irs)(cir, Expr(q"picklee"), fieldAccessor)}
      """
    }
    // TODO: this should go through HasPicklerDispatch
    def nonFinalDispatch() = {
      val nullDispatch = CaseDef(Literal(Constant(null)), EmptyTree, pickleAs(NullTpe))
      val compileTimeDispatch = compileTimeDispatchees(tpe) map (tpe => {
        CaseDef(Bind(TermName("pickleeClass"), Ident(nme.WILDCARD)), q"pickleeClass == classOf[$tpe]", pickleAs(tpe))
      })
      val runtimeDispatch = CaseDef(Ident(nme.WILDCARD), EmptyTree, q"""
        val runtimePickler = Pickler.genPickler(scala.reflect.runtime.currentMirror, pickleeClass)
        runtimePickler.pickle(pickleeRaw).asInstanceOf[${pickleType(pickleFormat)}]
      """)
      q"""
        val pickleeClass = if (pickleeRaw != null) pickleeRaw.getClass else null
        ${Match(q"pickleeClass", nullDispatch +: compileTimeDispatch :+ runtimeDispatch)}
      """
    }
    def finalDispatch() = {
      if (sym.isPrimitive || sym.isDerivedValueClass) pickleAs(tpe)
      else q"if (pickleeRaw != null) ${pickleAs(tpe)} else ${pickleAs(NullTpe)}"
    }
    val dispatchLogic = if (sym.isFinal) finalDispatch() else nonFinalDispatch()

    // TODO: genPickler and genUnpickler should really hoist their results to the top level
    // it should be a straightforward c.introduceTopLevel (I guess we can ignore inner classes in the paper)
    // then we would also not need this implicit val trick
    q"""
      import scala.pickling._
      implicit val anon$$pickler = new Pickler[$tpe] {
        type PickleType = ${pickleType(pickleFormat)}
        def pickle(pickleeRaw: Any): PickleType = {
          $dispatchLogic
        }
      }
      anon$$pickler
    """
  }
}

trait UnpicklerMacros extends Macro {
  def impl[T: c.WeakTypeTag]: c.Tree = {
    import c.universe._
    import definitions._
    val tpe = weakTypeOf[T]
    val sym = tpe.typeSymbol.asClass
    val expectsValueIR = tpe.typeSymbol.asClass.isPrimitive || tpe.typeSymbol == StringClass
    val expectsObjectIR = !expectsValueIR

    def unexpectedIR: c.Tree = q"""throw new PicklingException("unexpected IR: " + ir + " for type " + ${tpe.toString})"""

    def unpickleValueIR: c.Tree =
      tpe match {
        case tpe if tpe =:= IntClass.toType => q"vir.value.asInstanceOf[Double].toInt"
        case tpe if tpe =:= StringClass.toType => q"vir.value.asInstanceOf[String]"
        case _ => c.abort(c.enclosingPosition, s"don't know how to unpickle a ValueIR as $tpe")
      }

    def unpickleObjectIR: c.Tree = {
      def unpickleAs(tpe: Type) = {
        if (tpe.typeSymbol.asClass.typeParams.nonEmpty)
          c.abort(c.enclosingPosition, s"implementation restriction: cannot unpickle polymorphic type $tpe")
        val ctorSym = tpe.declaration(nme.CONSTRUCTOR).asMethod // TODO: multiple constructors
        def ctorArg(name: String, tpe: Type) = q"oir.fields($name).unpickle[$tpe]"
        val ctorArgs = ctorSym.paramss.flatten.map(f => ctorArg(f.name.toString, f.typeSignature)) // TODO: multiple argument lists
        q"new $tpe(..$ctorArgs)" // TODO: also support public vars
      }
      // TODO: this go through HasPicklerDispatch, probably routed through a companion of T
      def nonFinalDispatch() = {
        val compileTimeDispatch = compileTimeDispatchees(tpe) map (tpe => {
          CaseDef(Bind(TermName("clazz"), Ident(nme.WILDCARD)), q"clazz == classOf[$tpe]", unpickleAs(tpe))
        })
        val runtimeDispatch = CaseDef(Ident(nme.WILDCARD), EmptyTree, q"???")
        Match(q"oir.clazz", compileTimeDispatch :+ runtimeDispatch)
      }
      def finalDispatch() = unpickleAs(tpe)
      if (sym.isFinal) finalDispatch else nonFinalDispatch
    }

    q"""
      import scala.pickling._
      import scala.pickling.ir._
      implicit val anon$$unpickler = new Unpickler[$tpe] {
        def unpickle(ir: UnpickleIR): $tpe = ir match {
          case vir: ValueIR => ${if (expectsValueIR) unpickleValueIR else unexpectedIR}
          case oir: ObjectIR => ${if (expectsObjectIR) unpickleObjectIR else unexpectedIR}
          case _ => $unexpectedIR
        }
      }
      anon$$unpickler
    """
  }
}

trait PickleMacros extends Macro {
  def impl[T: c.WeakTypeTag](pickleFormat: c.Tree) = {
    import c.universe._
    val tpe = weakTypeOf[T]
    val q"${_}($picklee)" = c.prefix.tree
    q"implicitly[scala.pickling.Pickler[$tpe]].pickle($picklee).asInstanceOf[${pickleType(pickleFormat)}]"
  }
}

trait UnpickleMacros extends Macro {
  // TODO: implement this
  // override def onInfer(tic: c.TypeInferenceContext): Unit = {
  //   c.error(c.enclosingPosition, "must specify the type parameter for method unpickle")
  // }
  def pickleUnpickle[T: c.WeakTypeTag]: c.Tree = {
    import c.universe._
    val tpe = weakTypeOf[T]
    val pickleTree = c.prefix.tree

    q"""
      val pickle = $pickleTree
      new ${pickleFormatType(pickleTree)}().parse(pickle, getClass.getClassLoader) match {
        case Some(result) => result.unpickle[$tpe]
        case None => throw new PicklingException("failed to unpickle \"" + pickle + "\" as ${tpe.toString}")
      }
    """
  }
  def irUnpickle[T: c.WeakTypeTag]: c.Tree = {
    import c.universe._
    val tpe = weakTypeOf[T]
    val ir = c.prefix.tree
    q"implicitly[scala.pickling.Unpickler[$tpe]].unpickle($ir)"
  }
}

trait PickleableMacro extends AnnotationMacro {
  def impl = {
    import c.universe._
    import Flag._
    c.annottee match {
      case ClassDef(mods, name, tparams, Template(parents, self, body)) =>
        // TODO: implement dispatchTo, add other stuff @pickleable should do
        val dispatchTo = q"override def dispatchTo = ???"
        ClassDef(mods, name, tparams, Template(parents :+ tq"scala.pickling.HasPicklerDispatch", self, body :+ dispatchTo))
    }
  }
}
