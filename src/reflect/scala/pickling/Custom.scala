package scala.pickling

import scala.reflect.runtime.universe._
import ir._

abstract class RuntimePickler[T: TypeTag](val pickleFormat: PickleFormat) extends Pickler[T] {
  type PickleFormatType = PickleFormat
  type PickleType = Pickle

  val ru: scala.reflect.runtime.universe.type = scala.reflect.runtime.universe
  val irs = new ir.PickleIRs[ru.type](ru)
  import irs._

  def tpe: Type = typeOf[T]
  def fields: List[FieldIR]
  def pickleField(fir: FieldIR): Pickle

  override def pickle(pickleeRaw: Any): PickleType = {
    pickleFormat.formatRT(irs)(ClassIR(tpe, null, fields), pickleeRaw, pickleField)
  }
}

abstract class RuntimeUnpickler[T] extends Unpickler[T] {
  def unpickle(ir: UnpickleIR): T
}
