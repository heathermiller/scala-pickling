package scala.pickling
package ir

case class DehydratedType(tostring: String)
case class FieldIR(name: String, tpe: DehydratedType)
case class ObjectIR(tpe: DehydratedType, parent: ObjectIR, fields: List[FieldIR])
