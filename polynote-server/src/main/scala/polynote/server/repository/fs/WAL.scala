package polynote.server
package repository.fs

import java.nio.ByteBuffer
import java.time.Instant

import polynote.messages.{Message, Notebook}
import scodec.bits.{BitVector, ByteVector}
import scodec.{Attempt, codecs}
import scodec.stream.decode
import scodec.stream.decode.StreamDecoder
import zio.{RIO, Task, ZIO}
import zio.blocking.Blocking
import zio.clock.{Clock, currentDateTime}

import scala.util.Try

object WAL {

  val WALMagicNumber: Array[Byte] = Array(0x50, 0x4E, 0x57, 0x41, 0x4C) // PNWAL
  val WALVersion: Short = 1
  val WALVersionBytes: Array[Byte] = Array((WALVersion >> 8).toByte, (WALVersion & 0xFF).toByte)

  // Timestamp for each update is stored in 32 bits unsigned, epoch UTC seconds.
  // So we'll have to change the format by February of 2106. Apologies to my great-great-great grandchildren.
  private val instantCodec = codecs.uint32.exmap[Instant](
    epochSeconds => Attempt.fromTry(Try(Instant.ofEpochSecond(epochSeconds))),
    instant      => Attempt.successful(instant.getEpochSecond)
  )

  def encodeTimestamp(instant: Instant): Task[BitVector] =
    ZIO.fromEither(instantCodec.encode(instant).toEither)
      .mapError(err => new RuntimeException(err.message))

  val decoder: StreamDecoder[(Instant, Message)] = {
    val readMagic = decode.once(codecs.constant(ByteVector(WALMagicNumber)))
    val readVersion = decode.once(codecs.int16)
    val readMessage = instantCodec ~ Message.codec
    def readMessages(version: Int): StreamDecoder[(Instant, Message)] = version match {
      case 1 => decode.many(readMessage)
      case v => decode.raiseError(new Exception(s"Unknown WAL version $v"))
    }

    for {
      _       <- readMagic
      ver     <- readVersion
      message <- readMessages(ver)
    } yield message
  }

  trait WALWriter {
    protected def append(bytes: Array[Byte]): RIO[Blocking, Unit] = append(ByteBuffer.wrap(bytes))
    protected def append(bytes: BitVector): RIO[Blocking, Unit] = append(bytes.toByteBuffer)
    protected def append(bytes: ByteBuffer): RIO[Blocking, Unit]

    def writeHeader(notebook: Notebook): RIO[Blocking with Clock, Unit] =
      append(WALMagicNumber) *>
        append(WALVersionBytes) *>
        appendMessage(notebook.withoutResults)

    def appendMessage(message: Message): RIO[Blocking with Clock, Unit] = for {
      ts    <- currentDateTime.map(_.toInstant) >>= encodeTimestamp
      bytes <- Message.encode[Task](message)
      _     <- append(ts ++ bytes)
    } yield ()

    def sync(): RIO[Blocking, Unit]

    def close(): RIO[Blocking, Unit]
  }

  object WALWriter {
    object NoWAL extends WALWriter {
      override protected def append(bytes: ByteBuffer): RIO[Blocking, Unit] = ZIO.unit
      override def sync(): RIO[Blocking, Unit] = ZIO.unit
      override def close(): RIO[Blocking, Unit] = ZIO.unit
    }
  }
}
