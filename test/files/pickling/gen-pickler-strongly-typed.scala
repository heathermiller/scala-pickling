import scala.pickling._
import json._

class Person(val name: String, val age: Int)

object Test extends App {
  val pickle = new Person("Bob",83).pickle

  import scala.reflect.runtime.universe._
  def printStaticType[T: TypeTag](x: T) = println(typeOf[T])
  printStaticType(pickle)
}
