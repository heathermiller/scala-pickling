import scala.reflect.runtime.universe._

object Test extends App {
  def show(s: Symbol) = s"$s: private=${s.isPrivate} protected=${s.isProtected} privateWithin=${s.privateWithin}"
  (typeOf[java.io.File].declarations map show filter (_ contains "package ")).toList.sorted foreach println
}