import scala.pickling._
import json._

class Person(val name: String, val age: Int)

object Test extends App {

  val p = new Person("Bob",83)
  val pickle = p.pickle
  println(pickle.value)
}
