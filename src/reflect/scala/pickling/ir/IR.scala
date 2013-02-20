/*                     __                                               *\
**     ________ ___   / /  ___     Scala API                            **
**    / __/ __// _ | / /  / _ |    (c) 2003-2013, LAMP/EPFL             **
**  __\ \/ /__/ __ |/ /__/ __ |    http://scala-lang.org/               **
** /____/\___/_/ |_/____/_/ | |                                         **
**                          |/                                          **
\*                                                                      */

package scala.pickling
package ir

import scala.reflect.macros.Context


class IRs[C <: Context with Singleton](val ctx: C) {
  import ctx.universe._

  trait IR
  case class FieldIR(name: String, tpe: Type)
  case class ObjectIR(tpe: Type, parent: ObjectIR, fields: List[FieldIR]) extends IR

  type Q = List[FieldIR]
  type C = ObjectIR

  def freshName(suffix: String) = "$$expose$private$" + suffix

  /* Side-effecting: generates public accessor methods for private fields */
  def fields(tp: Type): Q =
    (tp.declarations
       .filter(sym => !sym.isMethod && sym.isTerm && (sym.asTerm.isVar || sym.asTerm.isParamAccessor)) // separate issue: minimal versus verbose PickleFormat . i.e. someone might want all concrete inherited fields in their pickle
       .map { field =>
         val fieldName = field.name.toString.trim
         val fieldTpe  = field.typeSignatureIn(tp)
         if (field.asTerm.getter.isPrivate) {
           debug("processing private field " + field.name)
           val fresh = freshName(fieldName)
           val method = DefDef(NoMods, TermName(fresh), List(), List(), TypeTree(), Ident(field.name))
           ctx.introduceMember(tp.typeSymbol, method)
           FieldIR(fresh, fieldTpe)
         } else
           FieldIR(fieldName, fieldTpe)
       }).toList

  def composition(f1: (Q, Q) => Q, f2: (C, C) => C, f3: C => List[C]) =
    (c: C) => f3(c).reverse.reduce[C](f2)

  val f1 = (q1: Q, q2: Q) => q1 ++ q2

  val f2 = (c1: C, c2: C) => ObjectIR(c2.tpe, c1, c2.fields)

  val f3 = (c: C) =>
    c.tpe.baseClasses
         .map(_.typeSignature)
         .map(tp => ObjectIR(tp, null, fields(tp)))

  val compose =
    composition(f1, f2, f3)

  val flatten: C => C = (c: C) =>
    if (c.parent != null) ObjectIR(c.tpe, c.parent, f1(c.fields, flatten(c.parent).fields))
    else c
}