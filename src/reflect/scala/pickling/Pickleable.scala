package scala.pickling

import java.lang.annotation.Inherited
import scala.annotation.MacroAnnotation
import scala.reflect.macros.Macro
import language.experimental.macros

// TODO: AnnotationMacro isn't implemented yet. Y U NO IMPLEMENT IT YET LOL?
// @Inherited
// class Pickleable extends MacroAnnotation {
//   def transform = macro PickleableMacro.impl
// }
// trait PickleableMacro extends AnnotationMacro
//   def impl = ???
// }

trait HasPicklerDispatch {
  def dispatchTo: Pickler[_]
}
