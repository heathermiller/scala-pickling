package scala.pickling

import scala.reflect.runtime.universe._
import language.experimental.macros

trait CorePicklersUnpicklers extends GenPicklers with GenUnpicklers {
  implicit def intPicklerUnpickler(implicit format: PickleFormat): Pickler[Int] with Unpickler[Int] = macro PrimitivePicklerUnpicklerMacro.impl[Int]
  implicit def stringPicklerUnpickler(implicit format: PickleFormat): Pickler[String] with Unpickler[String] = macro PrimitivePicklerUnpicklerMacro.impl[String]
  implicit def booleanPicklerUnpickler(implicit format: PickleFormat): Pickler[Boolean] with Unpickler[Boolean] = macro PrimitivePicklerUnpicklerMacro.impl[Boolean]
  implicit def nullPicklerUnpickler(implicit format: PickleFormat): Pickler[Null] with Unpickler[Null] = macro PrimitivePicklerUnpicklerMacro.impl[Null]
  // TODO: if you uncomment this one, it will shadow picklers/unpicklers for Int and String. why?!
  // TODO: due to the inability to implement module pickling/unpickling in a separate macro, I moved the logic into genPickler/genUnpickler
  // implicit def modulePicklerUnpickler[T <: Singleton](implicit format: PickleFormat): Pickler[T] with Unpickler[T] = macro ModulePicklerUnpicklerMacro.impl[T]
}

trait PrimitivePicklerUnpicklerMacro extends Macro {
  import c.universe._
  def impl[T: c.WeakTypeTag](format: c.Tree): c.Tree = {
    val tpe = weakTypeOf[T]
    generatePicklerUnpickler(tpe, format, tq"PrimitivePicklerUnpicklerMacro")
  }
  def pickle(pickleeRaw: c.Tree, builder: c.Tree): c.Tree = q"""
    $builder.beginEntry(scala.pickling.`package`.fastTypeTag[${dataType(c.prefix.tree)}], $pickleeRaw)
    $builder.endEntry()
  """
  def unpickle(tag: c.Tree, reader: c.Tree): c.Tree = q"""
    $reader.readPrimitive(scala.pickling.`package`.fastTypeTag[${dataType(c.prefix.tree)}])
  """
}

trait ModulePicklerUnpicklerMacro extends Macro {
  import c.universe._
  def impl[T: c.WeakTypeTag](format: c.Tree): c.Tree = {
    val tpe = weakTypeOf[T]
    val module = tpe.typeSymbol.asClass.module
    if (module == NoSymbol) c.diverge()
    generatePicklerUnpickler(tpe, format, tq"ModulePicklerUnpicklerMacro")
  }
  def pickle(pickleeRaw: c.Tree, builder: c.Tree): c.Tree = q"""
    $builder.beginEntry(scala.pickling.`package`.fastTypeTag[${c.prefix}.DataType], $pickleeRaw)
    $builder.endEntry()
  """
  def unpickle(tag: c.Tree, reader: c.Tree): c.Tree = q"""
    ${dataType(c.prefix.tree).typeSymbol.asClass.module}
  """
}
