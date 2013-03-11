/*                     __                                               *\
**     ________ ___   / /  ___     Scala API                            **
**    / __/ __// _ | / /  / _ |    (c) 2003-2013, LAMP/EPFL             **
**  __\ \/ /__/ __ |/ /__/ __ |    http://scala-lang.org/               **
** /____/\___/_/ |_/____/_/ | |                                         **
**                          |/                                          **
\*                                                                      */

package scala.pickling

package object json {
  import language.experimental.macros
  import scala.reflect.api.Universe
  import scala.reflect.macros.Context
  import ir._

  implicit val intPickler: Pickler[Int]         = new Pickler[Int]     { def pickle(x: Any) = new Pickle { val value: Any = x }}
  implicit val longPickler: Pickler[Long]       = new Pickler[Long]    { def pickle(x: Any) = new Pickle { val value: Any = x }}
  implicit val shortPickler: Pickler[Short]     = new Pickler[Short]   { def pickle(x: Any) = new Pickle { val value: Any = x }}
  implicit val doublePickler: Pickler[Double]   = new Pickler[Double]  { def pickle(x: Any) = new Pickle { val value: Any = x }}
  implicit val floatPickler: Pickler[Float]     = new Pickler[Float]   { def pickle(x: Any) = new Pickle { val value: Any = x }}
  implicit val booleanPickler: Pickler[Boolean] = new Pickler[Boolean] { def pickle(x: Any) = new Pickle { val value: Any = x }}
  implicit val bytePickler: Pickler[Byte]       = new Pickler[Byte]    { def pickle(x: Any) = new Pickle { val value: Any = x }}
  implicit val charPickler: Pickler[Char]       = new Pickler[Char]    { def pickle(x: Any) = new Pickle { val value: Any = "\"" + x.toString + "\""}}
  implicit val stringPickler: Pickler[String]   = new Pickler[String]  { def pickle(x: Any) = new Pickle { val value: Any = "\"" + x.toString + "\""}}

  implicit val pickleFormat = new JSONPickleFormat

  class JSONPickleFormat extends PickleFormat {
    override def instantiate = macro JSONPickleInstantiate.impl
    def pickle[U <: Universe with Singleton](irs: IRs[U])(ir: irs.ObjectIR, holes: List[irs.uni.Expr[Pickle]]): irs.uni.Expr[Pickle] = {
      import irs.uni._
      def genJsonAssembler() = {
        // TODO: using `obj` to refer to the value being pickled. needs a more robust approach
        val objTos = Expr[String](Select(Ident(TermName("obj")), TermName("toString")))
        val tpe = ir.tpe.typeSymbol.asType.toType
        if (tpe =:= typeOf[Char] || tpe =:= typeOf[String]) reify("\"" + objTos.splice + "\"") // TODO: escape
        else if (tpe.typeSymbol.asClass.isPrimitive) objTos // TODO: unit?
        else {
          def pickleTpe(tpe: Type): Expr[String] = {
            def loop(tpe: Type): String = tpe match {
              case TypeRef(_, sym, Nil) => s"${sym.fullName}"
              case TypeRef(_, sym, targs) => s"${sym.fullName}[${targs.map(targ => pickleTpe(targ))}]"
            }
            reify("\"tpe\": \"" + Expr[String](Literal(Constant(loop(tpe)))).splice + "\"")
          }
          def pickleField(name: String, hole: Expr[Pickle]) = reify("\"" + Expr[String](Literal(Constant(name))).splice + "\": " + hole.splice.value)
          val fragmentTrees = pickleTpe(tpe) +: (ir.fields.zip(holes).map{case (f, h) => pickleField(f.name, h)})
          val fragmentsTree = fragmentTrees.map(t => reify("  " + t.splice)).reduce((t1, t2) => reify(t1.splice + ",\n" + t2.splice))
          reify("{\n" + fragmentsTree.splice + "\n}")
        }
      }
      reify(new Pickle { val value = genJsonAssembler().splice })
    }

    type Data = String

    def objectPrefix(u: Universe)(tpe: u.Type): Data = "{\n  \"tpe\": \"" + tpe.typeSymbol.name.toString + "\",\n"
    def objectSuffix: Data                           = "\n}"
    def fieldSeparator: Data                         = ",\n"
    def fieldPrefix[U <: Universe with Singleton](irs: IRs[U])(fir: irs.FieldIR): Data =
      "  \"" + fir.name + "\": "
    def fieldSuffix: Data                            = ""

    def format[U <: Universe with Singleton](irs: IRs[U])(oir: irs.ObjectIR, pickle: irs.FieldIR => Pickle): Pickle =
      new Pickle {
        val value =
          objectPrefix(irs.uni)(oir.tpe) + {
            val formattedFields = oir.fields.map(fld => fieldPrefix(irs)(fld) + pickle(fld).value)
            formattedFields mkString fieldSeparator
          } + objectSuffix
      }

  }

  object JSONPickleInstantiate {
    def impl(c: Context) = c.universe.EmptyTree updateAttachment pickleFormat
  }
}


// "{ \"tpe\": \"" + pickleType(c)(tpe) + "\"\n" +
//           genFields(fields).splice(vals) + "\n" +
//           "}"
