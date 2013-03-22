import scala.pickling._
import binaryopt._

class MPair[A, B](val left: A, val right: B)

object Test extends App {

 val mp = new MPair(4, "hi")
 val p = mp.pickle

 println(p.value.mkString(","))

 val res = p.unpickle[MPair[Int, String]]
 println("unpickled: " + res)
}