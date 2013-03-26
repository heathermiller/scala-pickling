package scala.pickling

import scala.reflect.api.Universe
import scala.reflect.macros.Context
import scala.collection.mutable.{Map => MutableMap, ListBuffer => MutableList, WeakHashMap, Set => MutableSet}
import java.lang.ref.WeakReference
import scala.collection.mutable.{Stack => MutableStack}
import scala.reflect.runtime.universe._
import scala.reflect.synthetic._

object Tools {
  private val subclassCaches = new WeakHashMap[AnyRef, WeakReference[AnyRef]]()

  private object SomeRef {
    def unapply[T](optRef: Option[WeakReference[T]]): Option[T] =
      if (optRef.nonEmpty) {
        val result = optRef.get.get
        if (result != null) Some(result) else None
      } else None
  }

  def subclassCache(key: AnyRef, valueThunk: => AnyRef): AnyRef = {
    subclassCaches get key match {
      case SomeRef(value) =>
        value
      case _ =>
        val value = valueThunk
        subclassCaches(key) = new WeakReference(value)
        value
    }
  }

  // TODO: the standard "c.topLevelRef orElse c.introduceTopLevel" approach doesn't work with the runtime compiler
  // hence we should go for this hack. at least it's not going to OOM us...
  val generatedNames = MutableSet[Any]()
}

class Tools[U <: Universe with Singleton](val u: U) {
  import u._
  import definitions._

  def blackList(sym: Symbol) = sym == AnyClass || sym == AnyRefClass || sym == AnyValClass || sym == ObjectClass

  def isRelevantSubclass(baseSym: Symbol, subSym: Symbol) = {
    !blackList(baseSym) && !blackList(subSym) && subSym.isClass && {
      val subClass = subSym.asClass
      subClass.baseClasses.contains(baseSym) && !subClass.isAbstractClass && !subClass.isTrait
    }
  }

  def compileTimeDispatchees(tpe: Type, mirror: Mirror): List[Type] = {
    val nullTpe = if (tpe.baseClasses.contains(ObjectClass)) List(NullTpe) else Nil
    val subtypes = allStaticallyKnownConcreteSubclasses(tpe, mirror).filter(subtpe => subtpe.typeSymbol != tpe.typeSymbol)
    val selfTpe = if (isRelevantSubclass(tpe.typeSymbol, tpe.typeSymbol)) List(tpe) else Nil
    nullTpe ++ subtypes ++ selfTpe
  }

  def allStaticallyKnownConcreteSubclasses(tpe: Type, mirror: Mirror): List[Type] = {
    // TODO: so far the search is a bit dumb
    // given `class C[T]; class D extends C[Int]` and `tpe = C[String]`, it will return <symbol of D>
    // TODO: on a more elaborate note
    // given `class C; class D[T] extends C` we of course cannot return the infinite number of `D[X]` types
    // but what we can probably do is to additionally look up custom picklers/unpicklers of for specific `D[X]`
    val baseSym = tpe.typeSymbol

    def sourcepathScan(): List[Symbol] = {
      val g = u.asInstanceOf[scala.tools.nsc.Global]
      val subclasses = MutableList[g.Symbol]()
      def analyze(sym: g.Symbol) = if (isRelevantSubclass(baseSym, sym.asInstanceOf[Symbol])) subclasses += sym
      def loop(tree: g.Tree): Unit = tree match {
        // NOTE: only looking for top-level classes!
        case g.PackageDef(_, stats) => stats.foreach(loop)
        case cdef: g.ClassDef => analyze(cdef.symbol)
        case mdef: g.ModuleDef => analyze(mdef.symbol.moduleClass)
        case _ => // do nothing
      }
      g.currentRun.units.map(_.body).foreach(loop)
      subclasses.toList.asInstanceOf[List[u.Symbol]]
    }

    def sourcepathAndClasspathScan(): List[Symbol] = {
      lazy val classpathCache = Tools.subclassCache(mirror, {
        val cache = MutableMap[Symbol, MutableList[Symbol]]()
        def updateCache(bc: Symbol, c: Symbol) = {
          if (bc != c && isRelevantSubclass(bc, c)) // TODO: what else do we want to ignore?
            cache.getOrElseUpdate(bc, MutableList()) += c
        }
        def loop(pkg: Symbol): Unit = {
          // NOTE: only looking for top-level classes!
          val pkgMembers = pkg.typeSignature.members
          pkgMembers foreach (m => {
            def analyze(m: Symbol): Unit = {
              if (m.name.decoded.contains("$")) () // SI-7251
              else if (m.isClass) m.asClass.baseClasses foreach (bc => updateCache(bc, m))
              else if (m.isModule) analyze(m.asModule.moduleClass)
              else ()
            }
            analyze(m)
          })
          def recurIntoPackage(pkg: Symbol) = {
            pkg.name.toString != "_root_" &&
            pkg.name.toString != "quicktime" && // TODO: pesky thing on my classpath, crashes ClassfileParser
            pkg.name.toString != "j3d" && // TODO: another ClassfileParser crash
            pkg.name.toString != "jansi" && // TODO: and another one (jline.jar)
            pkg.name.toString != "jsoup" // TODO: SI-3809
          }
          val subpackages = pkgMembers filter (m => m.isPackage && recurIntoPackage(m))
          subpackages foreach loop
        }
        loop(mirror.RootClass)
        cache // NOTE: 126873 cache entries for my classpath
      }).asInstanceOf[MutableMap[Symbol, MutableList[Symbol]]]
      classpathCache.getOrElse(baseSym, Nil).toList
    }

    if (baseSym.isFinal || baseSym.isModuleClass) Nil // FIXME: http://groups.google.com/group/scala-internals/browse_thread/thread/e2b786120b6d118d
    else if (blackList(baseSym)) Nil
    else {
      var unsorted =
        u match {
          case u: scala.tools.nsc.Global if u.currentRun.compiles(baseSym.asInstanceOf[u.Symbol]) => sourcepathScan()
          case _ => sourcepathAndClasspathScan()
        }
      // NOTE: need to order the list: children first, parents last
      // otherwise pattern match which uses this list might work funnily
      val subSyms = unsorted.distinct.sortWith((c1, c2) => c1.asClass.baseClasses.contains(c2))
      val subTpes = subSyms.map(subSym => {
        val tpeWithMaybeTparams = subSym.asType.toType
        val tparams = tpeWithMaybeTparams match {
          case TypeRef(_, _, targs) => targs.map(_.typeSymbol)
          case _ => Nil
        }
        existentialAbstraction(tparams, tpeWithMaybeTparams)
      })
      // println(s"allStaticallyKnownConcreteSubclasses($tpe) = $subTpes")
      subTpes
    }
  }
}

abstract class Macro extends scala.reflect.macros.Macro {
  import c.universe._
  import definitions._

  val tools = new Tools[c.universe.type](c.universe)
  import tools._

  val irs = new ir.IRs[c.universe.type](c.universe)
  import irs._

  private def innerType(target: Tree, name: String): Type = {
    def fail(msg: String) = c.abort(c.enclosingPosition, s"$msg for ${target} of type ${target.tpe}")
    val carrier = c.typeCheck(tq"${target.tpe}#${TypeName(name)}", mode = c.TYPEmode, silent = true)
    carrier match {
      case EmptyTree => fail(s"Couldn't resolve $name")
      case tree => tree.tpe.normalize match {
        case tpe if tpe.typeSymbol.isClass => tpe
        case tpe => fail(s"$name resolved as $tpe is invalid")
      }
    }
  }

  def pickleFormatType(pickle: Tree): Type = innerType(pickle, "PickleFormatType")

  def compileTimeDispatchees(tpe: Type): List[Type] = tools.compileTimeDispatchees(tpe, rootMirror)

  def syntheticPackageName: String = "scala.pickling.synthetic"
  def syntheticBaseName(tpe: Type): TypeName = {
    def prettyName(sym: Symbol) = {
      val parts = sym.fullName.split('.')
      def encode(name: String) = TermName(name).encoded.toString
      parts.map(encode).map(_.capitalize).mkString("") + (if (sym.isModuleClass) "$" else "")
    }
    var erasedTypeName = prettyName(tpe.typeSymbol)
    if (tpe.typeSymbol == ArrayClass) erasedTypeName += prettyName(tpe.asInstanceOf[TypeRef].args.head.typeSymbol)
    TypeName(erasedTypeName)
  }
  def syntheticBaseQualifiedName(tpe: Type): TypeName = TypeName(syntheticPackageName + "." + syntheticBaseName(tpe).toString)

  def syntheticPicklerName(tpe: Type): TypeName = syntheticBaseName(tpe) + syntheticPicklerSuffix()
  def syntheticPicklerQualifiedName(tpe: Type): TypeName = syntheticBaseQualifiedName(tpe) + syntheticPicklerSuffix()
  def syntheticPicklerSuffix(): String = "Pickler"

  def syntheticUnpicklerName(tpe: Type): TypeName = syntheticBaseName(tpe) + syntheticUnpicklerSuffix()
  def syntheticUnpicklerQualifiedName(tpe: Type): TypeName = syntheticBaseQualifiedName(tpe) + syntheticUnpicklerSuffix()
  def syntheticUnpicklerSuffix(): String = "Unpickler"

  def syntheticPicklerUnpicklerName(tpe: Type): TypeName = syntheticBaseName(tpe) + syntheticPicklerUnpicklerSuffix()
  def syntheticPicklerUnpicklerQualifiedName(tpe: Type): TypeName = syntheticBaseQualifiedName(tpe) + syntheticPicklerUnpicklerSuffix()
  def syntheticPicklerUnpicklerSuffix(): String = "PicklerUnpickler"

  def preferringAlternativeImplicits(body: => Tree): Tree = {
    def debug(msg: Any) = {
      val padding = "  " * (c.enclosingImplicits.length - 1)
      // Console.err.println(padding + msg)
    }
    debug("can we enter " + c.enclosingImplicits.head.pt + "?")
    debug(c.enclosingImplicits)
    c.enclosingImplicits match {
      case c.ImplicitCandidate(_, _, ourPt, _) :: c.ImplicitCandidate(_, _, theirPt, _) :: _ if ourPt =:= theirPt =>
        debug(s"no, because: ourPt = $ourPt, theirPt = $theirPt")
        c.diverge()
        // c.abort(c.enclosingPosition, "stepping aside: repeating itself")
      case _ =>
        debug(s"not sure, need to explore alternatives")
        c.inferImplicitValue(c.enclosingImplicits.head.pt, silent = true) match {
          case success if success != EmptyTree =>
            debug(s"no, because there's $success")
            // c.abort(c.enclosingPosition, "stepping aside: there are other candidates")
            c.diverge()
          case _ =>
            debug("yes, there are no obstacles. entering " + c.enclosingImplicits.head.pt)
            val result = body
            debug("result: " + result)
            result
        }
    }
  }

  private var reflectivePrologueEmitted = false // TODO: come up with something better
  def reflectively(target: String, fir: FieldIR)(body: Tree => Tree): List[Tree] = reflectively(TermName(target), fir)(body)
  def reflectively(target: TermName, fir: FieldIR)(body: Tree => Tree): List[Tree] = {
    val prologue = {
      if (!reflectivePrologueEmitted) {
        reflectivePrologueEmitted = true
        val initMirror = q"""
          import scala.reflect.runtime.universe._
          val mirror = runtimeMirror(getClass.getClassLoader)
          val im = mirror.reflect($target)
        """
        initMirror.stats :+ initMirror.expr
      } else {
        Nil
      }
    }
    val field = fir.field.get
    val firSymbol = TermName(fir.name + "Symbol")
    // TODO: make sure this works for:
    // 1) private[this] fields
    // 2) inherited private[this] fields
    // 3) overridden fields
    val wrappedBody =
      q"""
        val $firSymbol = scala.pickling.`package`.fastTypeTag[${field.owner.asClass.toType.erasure}].tpe.member(TermName(${field.name.toString}))
        if ($firSymbol.isTerm) ${body(q"im.reflectField($firSymbol.asTerm)")}
      """
    prologue ++ wrappedBody.stats :+ wrappedBody.expr
  }

  def introduceTopLevel(pid: String, name: Name)(body: => ImplDef): Tree = {
    val fullName = if (name.isTermName) TermName(pid + "." + name) else TypeName(pid + "." + name)
    try {
      val existing = if (name.isTermName) c.mirror.staticModule(fullName.toString) else c.mirror.staticClass(fullName.toString)
      assert(existing != NoSymbol, fullName)
      // System.err.println(s"existing = $existing")
      Ident(existing)
    } catch {
      case _: scala.reflect.internal.MissingRequirementError =>
        if (!Tools.generatedNames(fullName)) {
          // System.err.println(s"introducing $pid.$name")
          c.introduceTopLevel(pid, body)
          Tools.generatedNames += fullName
        }
        c.topLevelRef(fullName)
    }
  }
}

trait FastTypeTagMacro extends Macro {
  def impl[T: c.WeakTypeTag]: c.Tree = {
    import c.universe._
    import definitions._
    val tpe = weakTypeOf[T]
    val wrapperPid = "scala.reflect.synthetic"
    val wrapperName = TermName("Reified" + syntheticBaseName(tpe))
    val wrapperRef =
      introduceTopLevel(wrapperPid, wrapperName) {
        val reifiedTpe = c.reifyType(treeBuild.mkRuntimeUniverseRef, EmptyTree, tpe, concrete = true)
        q"object $wrapperName { val tag = $reifiedTpe }"
      }
    q"$wrapperRef.tag.asInstanceOf[scala.reflect.runtime.universe.TypeTag[$tpe]]"
  }
}

trait PickleTools {
  private var hints = new Hints()
  case class Hints(
    tag: TypeTag[_] = null,
    knownSize: Int = -1,
    isStaticallyElidedType: Boolean = false,
    isDynamicallyElidedType: Boolean = false) {
    def isElidedType = isStaticallyElidedType || isDynamicallyElidedType
  }

  def hintTag(tag: TypeTag[_]): this.type = { hints = hints.copy(tag = tag); this }
  def hintKnownSize(knownSize: Int): this.type = { hints = hints.copy(knownSize = knownSize); this }
  def hintStaticallyElidedType(): this.type = { hints = hints.copy(isStaticallyElidedType = true); this }
  def hintDynamicallyElidedType(): this.type = { hints = hints.copy(isDynamicallyElidedType = true); this }

  def withHints[T](body: Hints => T): T = {
    val hints = this.hints
    this.hints = new Hints
    body(hints)
  }

  def typeToString(tpe: Type): String = {
    def loop(tpe: Type): String = {
      tpe match {
        case SingleType(pre, sym) => loop(TypeRef(pre, sym.asModule.moduleClass, Nil))
        case TypeRef(_, sym, Nil) => s"${sym.fullName}" + (if (sym.isModuleClass) "$" else "")
        // NOTE: we don't pickle targs, because of the erasure strategy we're employing to support polymorphics
        // case TypeRef(_, sym, targs) => loop(tpe.typeConstructor) + s"[${targs.map(targ => loop(targ))}]"
        case TypeRef(pre, sym, targs) => loop(TypeRef(pre, sym, Nil))
        case ExistentialType(tparams, restpe) => loop(restpe)
        case _ => throw new PicklingException(s"fatal: unknown type $tpe repesented as ${showRaw(tpe)}")
      }
    }
    loop(tpe)
  }

  def typeFromString(mirror: Mirror, tpe: String): Type = {
    val sym = if (tpe.endsWith("$")) mirror.staticModule(tpe.stripSuffix("$")).moduleClass else mirror.staticClass(tpe)
    sym.asType.toType
  }
}
