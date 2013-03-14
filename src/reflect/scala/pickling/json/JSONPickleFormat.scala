package scala.pickling

package object json {
  implicit val pickleFormat = new JSONPickleFormat
  implicit def toJSONPickle(value: String): JSONPickle = new JSONPickle(value)
}

package json {
  import language.experimental.macros
  import scala.collection.immutable.ListMap
  import scala.reflect.api.Universe
  import scala.reflect.runtime.{universe => ru}
  import scala.reflect.macros.Macro
  import scala.util.parsing.json._

  case class JSONPickle(val value: String) extends Pickle {
    type ValueType = String
    type PickleFormatType = JSONPickleFormat
  }

  class JSONPickleFormat extends PickleFormat {
    import ir._
    type PickleType = JSONPickle
    override def instantiate = macro JSONPickleInstantiate.impl
    def formatCT[U <: Universe with Singleton](irs: PickleIRs[U])(cir: irs.ClassIR, picklee: U#Expr[Any], fields: irs.FieldIR => U#Expr[Pickle]): U#Expr[JSONPickle] = {
      import irs.uni._
      import definitions._
      val sym = cir.tpe.typeSymbol.asClass
      val value: Expr[String] =
        if (sym == NullClass) reify("null")
        else if (sym == CharClass || sym == StringClass) reify("\"" + JSONFormat.quoteString(picklee.splice.toString) + "\"")
        else if (sym.isPrimitive) reify(picklee.splice.toString) // TODO: unit?
        else {
          def pickleTpe(tpe: Type): Expr[String] = reify("\"tpe\": \"" + Expr[String](Literal(Constant(tpe.erasure.typeSymbol.fullName.toString))).splice + "\"")
          def pickleField(fir: irs.FieldIR) = reify("\"" + Expr[String](Literal(Constant(fir.name))).splice + "\": " + fields(fir).splice.value)
          val fragmentTrees = pickleTpe(cir.tpe) +: cir.fields.map(fir => pickleField(fir))
          val fragmentsTree = fragmentTrees.map(t => reify("  " + t.splice)).reduce((t1, t2) => reify(t1.splice + ",\n" + t2.splice))
          reify("{\n" + fragmentsTree.splice + "\n}")
        }
      reify(JSONPickle(value.splice))
    }
    // def formatRT[U <: Universe with Singleton](irs: PickleIRs[U])(cir: irs.ClassIR, picklee: Any, fields: irs.FieldIR => Pickle): JSONPickle = {
    def formatRT[U <: Universe with Singleton](irs: PickleIRs[U])(cir: irs.ClassIR, picklee: Any, fields: irs.FieldIR => Pickle): Pickle = {
      def objectPrefix(tpe: irs.uni.Type) = "{\n  \"tpe\": \"" + tpe.typeSymbol.name.toString + "\",\n"
      val objectSuffix = "\n}"
      val fieldSeparator = ",\n"
      def fieldPrefix(fir: irs.FieldIR) = "  \"" + fir.name + "\": "
      val fieldSuffix = ""
      JSONPickle {
        objectPrefix(cir.tpe) + {
          val formattedFields = cir.fields.map(fld => fieldPrefix(fld) + fields(fld).value)
          formattedFields mkString fieldSeparator
        } + objectSuffix
      }
    }
    def parse(pickle: JSONPickle, classLoader: ClassLoader): Option[UnpickleIR] = {
      def unpickleTpe(stpe: String): Class[_] = classLoader.loadClass(stpe)
      def translate(parsedJSON: Any): UnpickleIR = parsedJSON match {
        case JSONObject(data) =>
          val tpe = unpickleTpe(data("tpe").asInstanceOf[String])
          val fields = ListMap() ++ data.map{case (k, v) => (k -> translate(v))} - "tpe"
          ObjectIR(tpe, fields)
        case JSONArray(data) =>
          throw new PicklingException(s"not yet implemented: $parsedJSON")
        case value =>
          ValueIR(value)
      }
      // TODO: hook into the JSON parser to construct UnpickleIR directly from JSONObject/JSONArray
      JSON.parseRaw(pickle.value).map(translate)
    }
  }

  trait JSONPickleInstantiate extends Macro {
    def impl = c.universe.EmptyTree updateAttachment pickleFormat
  }
}
