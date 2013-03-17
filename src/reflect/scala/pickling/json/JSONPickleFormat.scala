package scala.pickling

package object json {
  implicit val pickleFormat: JSONPickleFormat = new JSONPickleFormat
  implicit def toJSONPickle(value: String): JSONPickle = JSONPickle(value)
}

package json {
  import language.experimental.macros

  import scala.reflect.runtime.universe._
  import definitions._
  import scala.reflect.macros.Macro
  import scala.util.parsing.json._
  import ir._

  case class JSONPickle(value: String) extends Pickle {
    type ValueType = String
    type PickleFormatType = JSONPickleFormat
  }

  class JSONPickleFormat extends PickleFormat {
    type PickleType = JSONPickle

    type PickleBuilderType = JSONPickleBuilder
    def createBuilder() = new JSONPickleBuilder

    type PickleReaderType = JSONPickleReader
    def createReader(pickle: JSONPickle) = new JSONPickleReader(pickle)
  }

  class JSONPickleBuilder extends PickleBuilder {
    type PickleType = JSONPickle
    def beginEntry(tpe: Type, picklee: Any): this.type = ???
    def putField(name: String, pickler: this.type => Unit): this.type = ???
    def endEntry(): Unit = ???
    def result(): JSONPickle = ???
  }

  class JSONPickleReader(pickle: JSONPickle) extends PickleReader {
    def readType: Type = ???
    def atPrimitive: Boolean = ???
    def readPrimitive(tpe: Type): Any = ???
    def atObject: Boolean = ???
    def readField(name: String): this.type = ???
  }
}
