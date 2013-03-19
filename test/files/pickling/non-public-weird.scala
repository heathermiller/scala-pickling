import scala.pickling._
import json._

class Person(private val name: String, age: Int, val hobby: Hobby) {
  // NOTE: be careful not to reference age anywhere, so that it's elided by the "constructors" phase
  override def toString = s"Person(name = $name, hobby = $hobby)"
}
class Hobby(var name: String, private var notes: String, private val attitude: String) {
  override def toString = s"Hobby(name = $name, notes = $notes, attitude = $attitude)"
}

object Test extends App {
  val person = new Person("Eugene", 25, new Hobby("hacking", "mostly Scala", "loving it"))

  val personPickle = person.pickle
  println(personPickle)
  println(personPickle.unpickle[Person])

  // TODO: when generating a Person pickler at runtime, we should be able to reuse the one which was generated at compile-time
  // but unfortunately this doesn't happen. why?!
  val anyPickle = (person: Any).pickle
  println(anyPickle)
  println(anyPickle.unpickle[Person])
}
