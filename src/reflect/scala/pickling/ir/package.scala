package scala.pickling

import scala.reflect.api.Universe

package object ir {
  def dehydrate(u0: Universe)(tpe: u0.Type): DehydratedType = (new { val u: u0.type = u0 } with Dehydrator).apply(tpe)
  def hydrate(u0: Universe)(dtpe: DehydratedType): u0.Type = (new { val u: u0.type = u0 } with Dehydrator).unapply(dtpe).get
}

