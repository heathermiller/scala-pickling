package scala.pickling
package ir

import scala.collection.immutable.ListMap
import language.experimental.macros

sealed class UnpickleIR {
  def unpickle[T] = macro UnpickleMacros.irUnpickle[T]
}
case class ValueIR(value: Any) extends UnpickleIR
case class ObjectIR(clazz: Class[_], fields: ListMap[String, UnpickleIR]) extends UnpickleIR
