package scala.pickling

import scala.reflect.runtime.{universe => ru}
import scala.tools.reflect.ToolBox
import ir._

object PicklerRuntime {
  def genCompiledPickler(mirror: ru.Mirror, clazz: Class[_])(implicit format: PickleFormat, p1: Pickler[Int], p2: Pickler[String]): Pickler[_] = {
    // TODO: we should somehow cache toolboxes. maybe even inside the reflection API
    import scala.reflect.runtime.universe._
    val tb = mirror.mkToolBox()
    val tpe = mirror.classSymbol(clazz).asType.toType // TODO: what if the resulting class symbol is polymorphic?
    val formatTpe = mirror.reflect(format).symbol.asType.toType
    // TODO: toolbox bug. if we don't explicitly import PickleOps, it will fail to be found
    // more precisely: it will be found, but then immediately discarded, because a reference to it won't typecheck
    tb.eval(q"""
      import scala.pickling._
      import scala.pickling.`package`.PickleOps
      implicit val format: $formatTpe = new $formatTpe()
      Pickler.genPickler[$tpe]
    """).asInstanceOf[Pickler[_]]
  }

  def genInterpretedPickler(mirror: ru.Mirror, clazz: Class[_])(implicit format: PickleFormat, p1: Pickler[Int], p2: Pickler[String]): Pickler[_] = {
    // TODO: cover all primitive types
    val tpe = mirror.classSymbol(clazz).asType.toType // TODO: what if the resulting class symbol is polymorphic?
    if (tpe <:< ru.typeOf[Int])         implicitly[Pickler[Int]]
    else if (tpe <:< ru.typeOf[String]) implicitly[Pickler[String]]
    else {
      val irs = new PickleIRs[ru.type](ru)
      import irs._

      debug("creating IR for runtime pickler of type " + tpe)
      val cir = flatten(compose(ClassIR(tpe, null, List())))

      // build "interpreted" runtime pickler
      new Pickler[Any] {
        def pickle(picklee: Any): PickleType = {
          val im = mirror.reflect(picklee) // instance mirror

          format.formatRT(irs)(cir, picklee, (fld: irs.FieldIR) => {
            val fldAccessor = tpe.declaration(ru.newTermName(fld.name)).asTerm.accessed.asTerm
            val fldMirror   = im.reflectField(fldAccessor)
            val fldValue    = fldMirror.get
            debug("pickling field value: " + fldValue)
            val fldPickler  = genInterpretedPickler(mirror, mirror.runtimeClass(fld.tpe))
            fldPickler.pickle(fldValue)
          }).asInstanceOf[PickleType]
        }
      }
    }
  }
}