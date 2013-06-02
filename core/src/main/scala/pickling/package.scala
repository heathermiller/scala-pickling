package scala

import scala.language.experimental.macros
import scala.language.reflectiveCalls

import scala.reflect.macros.Context
import scala.reflect.runtime.universe._
import scala.reflect.runtime.{universe => ru}
import scala.annotation.implicitNotFound

import java.lang.annotation.Inherited
import scala.annotation.MacroAnnotation

package object pickling {

  // TOGGLE DEBUGGING
  var debugEnabled: Boolean = System.getProperty("pickling.debug", "false").toBoolean
  def debug(output: => String) = if (debugEnabled) println(output)

  implicit class PickleOps[T](picklee: T) {
    def pickle(implicit format: PickleFormat): Pickle = macro PickleMacros.pickle[T]
    def pickleInto(builder: PBuilder): Unit = macro PickleMacros.pickleInto[T]
  }

  implicit class RichSymbol(sym: scala.reflect.api.Symbols#Symbol) {
    def isEffectivelyFinal = sym.asInstanceOf[scala.reflect.internal.Symbols#Symbol].isEffectivelyFinal
    def isEffectivelyPrimitive = throw new Exception("use Type.isEffectivelyPrimitive instead")
    def isNotNull = sym.isClass && (sym.asClass.isPrimitive || sym.asClass.isDerivedValueClass)
  }

  var cachedMirror: ru.Mirror = null
  def currentMirror: ru.Mirror = macro impl
  def impl(c: Context): c.Tree = {
    import c.universe._
    val cachedMirror = q"scala.pickling.`package`.cachedMirror"
    q"""
      if ($cachedMirror != null) $cachedMirror
      else {
        $cachedMirror = scala.reflect.runtime.currentMirror
        $cachedMirror
      }
    """
  }

  def typeToString(tpe: Type): String = tpe.key

  def typeFromString(mirror: Mirror, stpe: String): Type = {
    val (ssym, stargs) = {
      val Pattern = """^(.*?)(\[(.*?)\])?$""".r
      def fail() = throw new PicklingException(s"fatal: cannot unpickle $stpe")
      stpe match {
        case Pattern("", _, _) => fail()
        case Pattern(sym, _, null) => (sym, Nil)
        case Pattern(sym, _, stargs) => (sym, stargs.split(",").map(_.trim).toList)
        case _ => fail()
      }
    }

    val sym = if (ssym.endsWith(".type")) mirror.staticModule(ssym.stripSuffix(".type")).moduleClass else mirror.staticClass(ssym)
    val tycon = sym.asType.toTypeConstructor
    appliedType(tycon, stargs.map(starg => typeFromString(mirror, starg)))
  }

  // FIXME: duplication wrt Tools, but I don't really fancy abstracting away this path-dependent madness
  implicit class RichType(tpe: Type) {
    import definitions._
    def key: String = {
      tpe match {
        case ExistentialType(tparams, TypeRef(pre, sym, targs))
        if targs.nonEmpty && targs.forall(targ => tparams.contains(targ.typeSymbol)) =>
          TypeRef(pre, sym, Nil).key
        case TypeRef(pre, sym, targs) if pre.typeSymbol.isModuleClass =>
          sym.fullName +
          (if (sym.isModuleClass) ".type" else "") +
          (if (targs.isEmpty) "" else targs.map(_.key).mkString("[", ",", "]"))
        case _ =>
          tpe.toString
      }
    }
    def isEffectivelyPrimitive: Boolean = tpe match {
      case TypeRef(_, sym: ClassSymbol, _) if sym.isPrimitive => true
      case TypeRef(_, sym, eltpe :: Nil) if sym == ArrayClass && eltpe.isEffectivelyPrimitive => true
      case _ => false
    }
  }

  implicit class RichFieldMirror(fm: FieldMirror) {
    // workaround for SI-7464
    def forcefulSet(value: Any): Unit = {
      import java.lang.reflect.{Field => jField}
      val jfield = fm.asInstanceOf[{ def jfield: jField }].jfield
      jfield.set(fm.receiver, value)
    }
  }
}

package pickling {

  /** A static pickler for type `T`. Its `pickle` method takes an object-to-be-pickled of
   *  static type `T`, and pickles it to an instance of `PBuilder`. In the process the object
   *  is turned into some external representation like a byte array. The particular external
   *  representation (the "pickle format") is defined by the `format` val member (of type
   *  `PickleFormat`).
   *
   *  This pickler requires that the dynamic type of the object-to-be-pickled is equal to
   *  the erasure of its static type `T`.
   */
  @implicitNotFound(msg = "Cannot generate a pickler for ${T}. Recompile with -Xlog-implicits for details")
  trait SPickler[T] {
    val format: PickleFormat
    def pickle(picklee: T, builder: PBuilder): Unit
  }

  /** A dynamic pickler for type `T`. Its `pickle` method takes an object-to-be-pickled of
   *  static type `T`, and pickles it to an instance of `PBuilder`. In the process the object
   *  is turned into some external representation like a byte array. The particular external
   *  representation (the "pickle format") is defined by the `format` val member (of type
   *  `PickleFormat`).
   *
   *  In contrast to static picklers (instances of type `SPickler[T]`), a dynamic pickler of
   *  type `DPickler[T]` pickles any object of type `T`.
   */
  @implicitNotFound(msg = "Cannot generate a DPickler for ${T}. Recompile with -Xlog-implicits for details")
  trait DPickler[T] {
    val format: PickleFormat
    def pickle(picklee: T, builder: PBuilder): Unit = macro PickleMacros.dpicklerPickle[T]
  }

  object DPickler {
    implicit def genDPickler[T](implicit format: PickleFormat): DPickler[T] = macro PicklerMacros.dpicklerImpl[T]
  }

  trait GenPicklers {
    implicit def genPickler[T](implicit format: PickleFormat): SPickler[T] = macro PicklerMacros.impl[T]
    // TODO: the primitive pickler hack employed here is funny, but I think we should fix this one
    // since people probably would also have to deal with the necessity to abstract over pickle formats
    def genPickler(classLoader: ClassLoader, clazz: Class[_])(implicit format: PickleFormat): SPickler[_] = {
      // println(s"generating runtime pickler for $clazz") // NOTE: needs to be an explicit println, so that we don't occasionally fallback to runtime in static cases
      //val runtime = new CompiledPicklerRuntime(classLoader, clazz)
      val runtime = new InterpretedPicklerRuntime(classLoader, clazz)
      runtime.genPickler
    }
  }

  object SPickler extends CorePicklersUnpicklers

  @implicitNotFound(msg = "Cannot generate an unpickler for ${T}. Recompile with -Xlog-implicits for details")
  trait Unpickler[T] {
    val format: PickleFormat
    def unpickle(tag: => FastTypeTag[_], reader: PReader): Any
  }

  trait GenUnpicklers {
    implicit def genUnpickler[T](implicit format: PickleFormat): Unpickler[T] = macro UnpicklerMacros.impl[T]
    def genUnpickler(mirror: Mirror, tag: FastTypeTag[_])(implicit format: PickleFormat): Unpickler[_] = {
      // println(s"generating runtime unpickler for ${tag.tpe}") // NOTE: needs to be an explicit println, so that we don't occasionally fallback to runtime in static cases
      //val runtime = new CompiledUnpicklerRuntime(mirror, tag.tpe)
      val runtime = new InterpretedUnpicklerRuntime(mirror, tag)
      runtime.genUnpickler
    }
  }

  object Unpickler extends CorePicklersUnpicklers

  trait Pickle {
    type ValueType
    val value: ValueType

    type PickleFormatType <: PickleFormat
    def unpickle[T] = macro UnpickleMacros.pickleUnpickle[T]
  }

  trait PickleFormat {
    type PickleType <: Pickle
    def createBuilder(): PBuilder
    def createReader(pickle: PickleType, mirror: Mirror): PReader
  }

  trait Hintable {
    def hintTag(tag: FastTypeTag[_]): this.type
    def hintKnownSize(knownSize: Int): this.type
    def hintStaticallyElidedType(): this.type
    def hintDynamicallyElidedType(): this.type
    def pinHints(): this.type
    def unpinHints(): this.type
  }

  trait PBuilder extends Hintable {
    def beginEntry(picklee: Any): this.type
    def putField(name: String, pickler: this.type => Unit): this.type
    def endEntry(): Unit
    def beginCollection(length: Int): this.type
    def putElement(pickler: this.type => Unit): this.type
    def endCollection(length: Int): Unit
    def result(): Pickle
  }

  trait PReader extends Hintable {
    def mirror: Mirror
    def beginEntry(): FastTypeTag[_]
    def beginEntryNoTag(): String
    def atPrimitive: Boolean
    def readPrimitive(): Any
    def atObject: Boolean
    def readField(name: String): PReader
    def endEntry(): Unit
    def beginCollection(): PReader
    def readLength(): Int
    def readElement(): PReader
    def endCollection(): Unit
    def unpickle[T]: T = macro UnpickleMacros.readerUnpickle[T]
    def unpickleTopLevel[T]: T = macro UnpickleMacros.readerUnpickleTopLevel[T]
  }

  /** This annotation enables library authors to guarantee to their clients that
   *  picklers for separately-compiled subclasses are fully generated at compile-time.
   *
   *  This annotation adds a method that returns a pickler specialized for the runtime
   *  class of the annotated type. Note that the annotation is expanded in each subclass,
   *  transitively.
   */
  @Inherited class pickleable extends MacroAnnotation {
    def transform = macro PickleableMacro.impl
  }

  // NOTE: can't call it Pickleable because of a name clash w.r.t pickleable on case-insensitive file systems
  trait PickleableBase {
    def pickler: SPickler[_]
    def unpickler: Unpickler[_]
  }

  case class PicklingException(msg: String) extends Exception(msg)
}
