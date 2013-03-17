package scala.pickling

import scala.reflect.runtime.universe._

trait CorePicklersUnpicklers {
  // TODO: since we don't know precise types of builder and reader, we can't do optimizations here!!
  // I think we can fix this problem with type macros, so let's not worry much for now - I'll handle it when looking into custom picklers
  class PrimitivePicklerUnpickler[T: TypeTag](implicit val format: PickleFormat) extends Pickler[T] with Unpickler[T] {
    type PickleFormatType = PickleFormat
    type PickleBuilderType = PickleBuilder
    def pickle(picklee: Any, builder: PickleBuilderType): Unit = {
      val tpe = typeOf[T]
      builder.beginEntry(tpe, picklee)
      builder.endEntry()
    }
    type PickleReaderType = PickleReader
    def unpickle(tpe: Type, reader: PickleReaderType): Any = {
      // NOTE: here we essentially require that ints and strings are primitives for all readers
      // TODO: discuss that and see whether it defeats all the purpose of abstracting primitives away from picklers
      reader.readPrimitive(tpe)
    }
  }

  implicit def intPicklerUnpickler(implicit format: PickleFormat): PrimitivePicklerUnpickler[Int] = new PrimitivePicklerUnpickler[Int]()
  implicit def stringPicklerUnpickler(implicit format: PickleFormat): PrimitivePicklerUnpickler[String] = new PrimitivePicklerUnpickler[String]()
}