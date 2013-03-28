package scala.pickling

package object binary {
  implicit val pickleFormat = new BinaryPickleFormat
  implicit def toBinaryPickle(value: Array[Byte]): BinaryPickle = BinaryPickle(value)
}

package binary {
  import scala.reflect.runtime.{universe => ru}
  import scala.reflect.runtime.universe._

  case class BinaryPickle(value: Array[Byte]) extends Pickle {
    type ValueType = Array[Byte]
    type PickleFormatType = BinaryPickleFormat
    override def toString = s"""BinaryPickle(${value.mkString("[", ",", "]")})"""
  }

  class BinaryPickleBuilder(format: BinaryPickleFormat) extends PickleBuilder with PickleTools {
    import format._

    private var byteBuffer: ByteBuffer = _
    private var pos = 0

    private def formatType(tpe: Type): Array[Byte] = {
      typeToString(tpe).getBytes("UTF-8")
    }

    private def mkByteBuffer(knownSize: Int) = {
      if (byteBuffer == null) {
        byteBuffer =
          if (knownSize != -1) new ByteArray(knownSize)
          else new ByteArrayBuffer
      }
    }

    // PERF: this creates a closure object and passes it to withHints
    def beginEntry(picklee: Any): this.type = withHints { hints =>
      mkByteBuffer(hints.knownSize)

      if (picklee == null) {
        pos = byteBuffer.encodeByteTo(pos, NULL_TAG)
      } else {
        def writeTpe() = {
          val tpe = hints.tag.tpe // PERF bottleneck
          //println("writing tpe: " + tpe)
          val tpeBytes = formatType(tpe)
          //pos = byteBuffer.encodeIntTo(pos, tpeBytes.length)
          byteBuffer.encodeIntAtEnd(pos, tpeBytes.length)
          pos += 4
          pos = byteBuffer.copyTo(pos, tpeBytes)
        }

        hints.tag.key match { // PERF: should store typestring once in hints.
          case KEY_NULL =>
            if (!hints.isElidedType) writeTpe()
            pos = byteBuffer.encodeByteTo(pos, NULL_TAG)
          case KEY_INT =>
            // PERF: why would Int ever be not elided?
            //if (!hints.isElidedType) writeTpe()
            //byteBuffer.encodeIntTo(pos, picklee.asInstanceOf[Int])
            //pos = pos + 4
            byteBuffer.encodeIntAtEnd(pos, picklee.asInstanceOf[Int])
            pos += 4
          case KEY_BOOLEAN =>
            // PERF: why would Boolean ever be not elided?
            //if (!hints.isElidedType) writeTpe()
            pos = byteBuffer.encodeBooleanTo(pos, picklee.asInstanceOf[Boolean])
          case KEY_SCALA_STRING | KEY_JAVA_STRING =>
            // PERF: why would String ever be not elided?
            //if (!hints.isElidedType) writeTpe()
            pos = byteBuffer.encodeStringTo(pos, picklee.asInstanceOf[String])
          case _ =>
            if (!hints.isElidedType) {
              //println("!!!!!! calling writeTpe")
              writeTpe()
            }
            else pos = byteBuffer.encodeByteTo(pos, ELIDED_TAG)
        }
      }

      this
    }

    def putField(name: String, pickler: this.type => Unit): this.type = {
      // can skip writing name if we pickle/unpickle in the same order
      pickler(this)
      this
    }

    def endEntry(): Unit = { /* do nothing */ }

    var beginCollPos = 0

    def beginCollection(length: Int): this.type = {
      beginCollPos = pos
      //pos = byteBuffer.encodeIntTo(pos, length)
      byteBuffer.encodeIntAtEnd(pos, 0)
      pos += 4
      this
    }

    def putElement(pickler: this.type => Unit): this.type = {
      pickler(this)
      this
    }

    def endCollection(length: Int): Unit = {
      byteBuffer.encodeIntTo(beginCollPos, length)
    }

    def result() = {
      BinaryPickle(byteBuffer.toArray)
    }
  }

  class BinaryPickleReader(arr: Array[Byte], val mirror: Mirror, format: BinaryPickleFormat) extends PickleReader with PickleTools {
    import format._

    private val byteBuffer: ByteBuffer = new ByteArray(arr)
    private var pos = 0
    private var lastTagRead: TypeTag[_] = null

    def beginEntryNoTag(): String = withHints { hints =>
        if (hints.tag.key == KEY_SCALA_STRING || hints.tag.key == KEY_JAVA_STRING) {
          val (lookahead, newpos) = byteBuffer.decodeByteFrom(pos)
          lookahead match {
            case NULL_TAG =>
              pos = newpos
              "scala.Null"
            case _ =>
              hints.tag.key
          }
        } else if (hints.isElidedType && primitives.contains(hints.tag.key)) {
          hints.tag.key
        } else {
          val (lookahead, newpos) = byteBuffer.decodeByteFrom(pos)
          lookahead match {
            case NULL_TAG =>
              pos = newpos
              "scala.Null"
            case ELIDED_TAG =>
              pos = newpos
              hints.tag.key
            case _ =>
              //PERF
              val (typeString, newpos) = byteBuffer.decodeStringFrom(pos)
              pos = newpos
              //println("fast yay")
              typeString
          }
        }
    }


    def beginEntry(): TypeTag[_] = withHints { hints =>
      //println("JJJJJJJ")
      //println("TAG: " + hints.tag)
      lastTagRead = {
        if (hints.tag.key == KEY_SCALA_STRING || hints.tag.key == KEY_JAVA_STRING) {
          val (lookahead, newpos) = byteBuffer.decodeByteFrom(pos)
          lookahead match {
            case NULL_TAG =>
              pos = newpos
              TypeTag.Null
            case _ =>
              hints.tag
          }
        } else if (hints.isElidedType && primitives.contains(hints.tag.key)) {
          hints.tag
        } else {
          val (lookahead, newpos) = byteBuffer.decodeByteFrom(pos)
          lookahead match {
            case NULL_TAG =>
              pos = newpos
              TypeTag.Null
            case ELIDED_TAG =>
              pos = newpos
              hints.tag
            case _ =>
              //PERF
              //println("elided: " + hints.isElidedType)
              val (typeString, newpos) = byteBuffer.decodeStringFrom(pos)
              pos = newpos
              if (hints.isElidedType) hints.tag
              else {
                //println("DECODING TAG")
                TypeTag(typeFromString(mirror, typeString), typeString)
              }
          }
        }
      }
      lastTagRead
    }

    def atPrimitive: Boolean = primitives.contains(lastTagRead.key)

    def readPrimitive(): Any = {
      val (res, newpos) = {
        lastTagRead.key match {
          case KEY_NULL => (null, pos)
          case KEY_INT => byteBuffer.decodeIntFrom(pos)
          case KEY_BOOLEAN => byteBuffer.decodeBooleanFrom(pos)
          case KEY_SCALA_STRING | KEY_JAVA_STRING => byteBuffer.decodeStringFrom(pos)
        }
      }
      pos = newpos
      res
    }

    def atObject: Boolean = !atPrimitive

    def readField(name: String): BinaryPickleReader =
      this

    def endEntry(): Unit = { /* do nothing */ }

    def beginCollection(): PickleReader = this

    def readLength(): Int = {
      val (length, newpos) = byteBuffer.decodeIntFrom(pos)
      pos = newpos
      length
    }

    def readElement(): PickleReader = this

    def endCollection(): Unit = { /* do nothing */ }
  }

  class BinaryPickleFormat extends PickleFormat {
    val ELIDED_TAG: Byte = -1
    val NULL_TAG: Byte = -2

    val KEY_NULL = typeTag[Null].key
    val KEY_INT = typeTag[Int].key
    val KEY_BOOLEAN = typeTag[Boolean].key
    val KEY_SCALA_STRING = typeTag[scala.Predef.String].key
    val KEY_JAVA_STRING = typeTag[java.lang.String].key
    val primitives = Set(KEY_NULL, KEY_INT, KEY_BOOLEAN, KEY_SCALA_STRING, KEY_JAVA_STRING)

    type PickleType = BinaryPickle
    def createBuilder() = new BinaryPickleBuilder(this)
    def createReader(pickle: PickleType, mirror: Mirror) = new BinaryPickleReader(pickle.value, mirror, this)
  }
}
