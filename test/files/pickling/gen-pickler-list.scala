import scala.pickling._
import json._

object Test extends App {
  val pickle = List(1, 2).pickle
  println(pickle)
  // println(pickle.unpickle)
}
