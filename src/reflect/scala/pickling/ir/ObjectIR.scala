package scala.pickling
package ir

case class ObjectIR(tpe: DehydratedType, parent: ObjectIR, fields: List[FieldIR])
