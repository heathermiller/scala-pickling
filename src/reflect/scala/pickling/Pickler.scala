package scala.pickling

import language.experimental.macros
import scala.reflect.macros.Macro

trait Pickler[T] {
  def pickle(obj: T): Pickle
  def unpickle(p: Pickle): T
}

object Pickler {
  implicit def genPickler[T]: Pickler[T] = macro GenPicklerMacro.impl[T]
}

trait GenPicklerMacro extends Macro {
  import c.universe._
  def impl[T: c.WeakTypeTag] = ???
}