import scala.pickling._
import json._
import ir._

case class Person(val name: String, val age: Int)
object Person {
  implicit def personPickler(implicit pickleFormat: PickleFormat) = new RuntimePickler[Person](pickleFormat) {
    def fields = Nil
    def pickleField(fir: irs.FieldIR) = ???
  }
  implicit def personUnpickler = new RuntimeUnpickler[Person] {
    def unpickle(ir: UnpickleIR): Person = Person("Bob",83)
  }
}

object Test extends App {
  val pickle = Person("Bob",83).pickle
  println(pickle.value)
  println(pickle.value.unpickle[Person])
}
