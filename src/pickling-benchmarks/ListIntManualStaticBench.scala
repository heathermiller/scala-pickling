package bench

import scala.pickling._
import binary._

import scala.reflect.runtime.universe._
import scala.collection.mutable.ListBuffer

object ListIntManualStaticBench extends testing.Benchmark {

  val lst = (1 to 1000000).toList
  //val lst = (1 to 3).toList

  val tag = typeTag[Int]

  mirror = scala.reflect.runtime.currentMirror

  implicit def genListPickler[T](implicit format: PickleFormat): HandwrittenListIntPicklerUnpickler = new HandwrittenListIntPicklerUnpickler
  class HandwrittenListIntPicklerUnpickler(implicit val format: PickleFormat) extends Pickler[List[Int]] with Unpickler[List[Int]] {
    def pickle(picklee: ::[Int], builder: PickleBuilder): Unit = {
      builder.beginEntry(picklee) // without arg?
      //val arr = picklee.toArray
      //val length = arr.length

      //val len = picklee.length
      builder.beginCollection(0)
      builder.hintStaticallyElidedType()
      builder.hintTag(tag)
      builder.pinHints()

      //builder.hintKnownSize(54 + 4 + (len * 4))

      var elem = 0
      var rest: List[Int] = picklee
      
      var i: Int = 0
      while (rest.nonEmpty) {
        elem = rest.head
        rest = rest.tail
        builder.beginEntry(elem) // why are we using beginEntry if we have beginCollection before?
        builder.endEntry()
        i = i + 1
      }

      builder.unpinHints()
      builder.endCollection(i)
      builder.endEntry()
    }
    // tag is ignored!
    def unpickle(tag: => TypeTag[_], reader: PickleReader): Any = {
      val arrReader = reader.beginCollection()
      arrReader.hintStaticallyElidedType()
      arrReader.hintTag(typeTag[Int])
      arrReader.pinHints()

      val buffer = ListBuffer[Int]()
      val length = arrReader.readLength()
      var i = 0
      while (i < length) {
        arrReader.beginEntry()
        buffer += arrReader.readPrimitive().asInstanceOf[Int]
        arrReader.endEntry()
        i = i + 1
      }

      arrReader.unpinHints() // what does that do?
      arrReader.endCollection()
      buffer.toList // OK, constant time
    }
  }

  override def run() {
    val pickler = implicitly[Pickler[List[Int]]]
    val builder = pickler.format.asInstanceOf[BinaryPickleFormat].createBuilder()
    pickler.pickle(lst, builder)
    val pickle: BinaryPickle = builder.result

    //println(show(pickle.value))
    //val res = pickle.unpickleStatic[::[Int]]
    //val res = pickle.unpickle[List[Int]]
    val unpickler = implicitly[Unpickler[List[Int]]]
    val reader = unpickler.format.createReader(pickle.asInstanceOf[unpickler.format.PickleType], mirror)
    val res = reader.unpickle[List[Int]]
    
    println(res.size)
  }
}
