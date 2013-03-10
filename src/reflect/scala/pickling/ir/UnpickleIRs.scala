/*                     __                                               *\
**     ________ ___   / /  ___     Scala API                            **
**    / __/ __// _ | / /  / _ |    (c) 2003-2013, LAMP/EPFL             **
**  __\ \/ /__/ __ |/ /__/ __ |    http://scala-lang.org/               **
** /____/\___/_/ |_/____/_/ | |                                         **
**                          |/                                          **
\*                                                                      */

package scala.pickling
package ir

import scala.reflect.runtime.universe._
import scala.collection.immutable.ListMap
import language.experimental.macros

sealed class UnpickleIR {
  def unpickle[T] = macro UnpickleMacros.irUnpickle[T]
}
case class ValueIR(value: Any) extends UnpickleIR
case class ObjectIR(tpe: Type, fields: ListMap[String, UnpickleIR]) extends UnpickleIR
