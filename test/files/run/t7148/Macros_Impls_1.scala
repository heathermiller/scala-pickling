import scala.reflect.macros.Context
import language.experimental.macros

class C

object Macros {
  def tm1Impl(c: Context) = {
    import c.universe._
    val Template(parents, self, defs) = c.enclosingTemplate
    c.echo(NoPosition, "TM1 evaluated")
    Template(
      Nil,
      self, defs :+ q"def f: Int = 42")
  }

  type TM1 = macro tm1Impl
}