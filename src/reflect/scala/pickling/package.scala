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

package object pickling {
  // TOGGLE DEBUGGING
  var debugEnabled: Boolean = System.getProperty("pickling.debug", "false").toBoolean
  def debug(output: => String) = if (debugEnabled) println(output)

  implicit class PickleOps[T](picklee: T) {
    // needs to be a macro in order to provide the most precise return type possible
    def pickle(implicit pickler: Pickler[T]): _ = macro PickleMacros.impl[T]
  }
}

package pickling {

  trait Pickler[T] {
    type PickleType <: Pickle
    def pickle(obj: Any): PickleType
    // def unpickle(p: PickleType): T
  }

  object Pickler {
    implicit def genPickler[T]: Pickler[T] = macro GenPicklerMacro.impl[T]
  }

  trait Pickle {
    type ValueType
    val value: ValueType
  }

  @Inherited
  class pickleable extends MacroAnnotation {
    def transform = macro PickleableMacro.impl
  }

  trait HasPicklerDispatch {
    def dispatchTo: Pickler[_]
  }

  trait PickleFormat {
    import ir._
    type PickleType <: Pickle
    def instantiate = macro ???
    def pickle[U <: Universe with Singleton](irs: IRs[U])(ir: irs.ObjectIR, holes: List[irs.uni.Expr[PickleType]]): irs.uni.Expr[PickleType]
    // def unpickle[U <: Universe with Singleton](u: U)(pickle: u.Expr[PickleType]): u.Expr[(ru.Type, Any)]
  }
}


