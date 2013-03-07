package scala.pickling

import scala.reflect.api.Universe
import ir._

trait PickleFormat {
  // TODO: maybe we can have these as u.Expr's to document the types here, but currently that's too much of a hassle
  // why? reason #1: quasiquotes work with trees and macros no longer require exprs, so exprs are boilerplate
  // reason #2: one needs a TreeCreator to create u.Expr (as opposed to c.Expr), which is even more boilerplate
  // def pickle[U <: Universe with Singleton](u: U)(ir: ObjectIR, holes: List[u.Expr[Pickle]]): u.Expr[Pickle]
  // def unpickle[U <: Universe with Singleton](u: U)(pickle: u.Expr[Pickle]): u.Expr[(DehydratedType, Any)]
  def pickle[U <: Universe with Singleton](u: U)(ir: ObjectIR, holes: List[u.Tree]): u.Tree
  def unpickle[U <: Universe with Singleton](u: U)(pickle: u.Tree): u.Tree
}
