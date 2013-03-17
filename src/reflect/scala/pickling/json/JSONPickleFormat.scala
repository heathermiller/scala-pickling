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
  import scala.collection.mutable.{StringBuilder, Stack}
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
    type PickleFormatType = JSONPickleFormat
    implicit val format = json.pickleFormat
    type PickleType = JSONPickle

    private val buf = new StringBuilder()
    private val stack = new Stack[Type]()
    private def isJSONPrimitive(tpe: Type) = {
      val sym = tpe.typeSymbol.asClass
      sym == NullClass || sym.isPrimitive || sym == StringClass
    }

    def beginEntry(tpe: Type, picklee: Any): this.type = {
      stack.push(tpe)
      val sym = tpe.typeSymbol.asClass
      if (isJSONPrimitive(tpe)) {
        if (sym == NullClass) buf ++= "null"
        else if (sym == CharClass || sym == StringClass) buf ++= "\"" + JSONFormat.quoteString(picklee.toString) + "\""
        else buf ++= picklee.toString // TODO: unit?
      } else {
        buf ++= "{\n"
        def pickleTpe(tpe: Type): String = {
          def loop(tpe: Type): String = tpe match {
            case TypeRef(_, sym, Nil) => s"${sym.fullName}"
            case TypeRef(_, sym, targs) => s"${sym.fullName}[${targs.map(targ => pickleTpe(targ))}]"
          }
          "  \"tpe\": \"" + loop(tpe) + "\""
        }
        buf ++= pickleTpe(tpe)
      }
      this
    }
    def putField(name: String, pickler: this.type => Unit): this.type = {
      assert(!isJSONPrimitive(stack.top), stack.top)
      buf ++= ",\n  \"" + name + "\": "
      pickler(this)
      this
    }
    def endEntry(): Unit = {
      val tpe = stack.pop()
      if (isJSONPrimitive(tpe)) () // do nothing
      else buf ++= "\n}"
    }
    def result(): JSONPickle = {
      assert(stack.isEmpty, stack)
      JSONPickle(buf.toString)
    }
  }

  class JSONPickleReader(pickle: JSONPickle) extends PickleReader {
    type PickleFormatType = JSONPickleFormat
    implicit val format = json.pickleFormat
    def readType: Type = ???
    def atPrimitive: Boolean = ???
    def readPrimitive(tpe: Type): Any = ???
    def atObject: Boolean = ???
    def readField(name: String): this.type = ???
  }
}
