package io.github.mkotsur

import java.io.{ByteArrayOutputStream, InputStream, OutputStream}
import java.util.concurrent.TimeoutException

import ch.qos.logback.classic.Level
import com.amazonaws.services.lambda.runtime.Context
import io.circe.generic.auto._
import io.github.mkotsur.LambdaTest._
import io.github.mkotsur.aws.handler.Lambda
import io.github.mkotsur.aws.handler.Lambda._
import io.github.mkotsur.logback.TestAppender
import org.scalatest._
import org.scalatest.concurrent.Eventually
import org.mockito.MockitoSugar
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

object LambdaTest {

  class PingPong extends Lambda[Ping, Pong]() {
    override def handle(ping: Ping) = Right(Pong(ping.inputMsg.reverse))
  }

  class PingPongWithError extends Lambda[Ping, Pong] {
    override def handle(ping: Ping) = Left(new Error("PingPongWithError: Oops"))
  }

  class PingPongThrowingAnError extends Lambda[Ping, Pong] {
    override def handle(ping: Ping) = throw new Error("PingPongThrowingAnError: Oops")
  }

  class StringPong extends Lambda[String, Pong] {
    override def handle(input: String) = Right(Pong(input.toUpperCase()))
  }

  class PingString extends Lambda[Ping, String] {
    override def handle(input: Ping) = Right(input.inputMsg.toLowerCase())
  }

  class PingNothing extends Lambda[Ping, Unit] {
    override def handle(input: Ping) = Right(())
  }

  class NothingPong extends Lambda[Unit, Pong] {
    override def handle(n: Unit) = Right(Pong("nothing"))
  }

  class SeqSeq extends Lambda[Seq[String], Seq[Int]] {
    override def handle(strings: Seq[String]): Either[Throwable, Seq[Int]] =
      Try {
        strings.map(_.toInt)
      } match {
        case Success(v)  => Right(v)
        case Failure(ex) => Left(ex)
      }
  }

  class OptionOption extends Lambda[Option[Ping], Option[Pong]] {
    override protected def handle(input: Option[Ping]) = Right(
      input.map { ping =>
        Pong(ping.inputMsg.length.toString)
      }
    )
  }

  case class Ping(inputMsg: String)

  case class Pong(outputMsg: String)

}

class LambdaTest extends AnyFunSuite with should.Matchers with MockitoSugar with OptionValues with Eventually {

  test("should convert input/output to/from case classes") {

    val is = new StringInputStream("""{ "inputMsg": "hello" }""")
    val os = new ByteArrayOutputStream()

    new PingPong().handle(is, os, mock[Context])

    os.toString shouldBe """{"outputMsg":"olleh"}"""
  }

  test("should allow to call 'handle()' via reflection") {
    val handlerClass  = Class.forName(classOf[PingPong].getName)
    val handlerMethod = handlerClass.getMethod("handle", classOf[InputStream], classOf[OutputStream], classOf[Context])

    val handlerInstance = handlerClass.getConstructor().newInstance()

    val is = new StringInputStream("""{ "inputMsg": "hello" }""")
    val os = new ByteArrayOutputStream()

    handlerMethod.invoke(handlerInstance, is, os, mock[Context])

    os.toString shouldBe """{"outputMsg":"olleh"}"""
  }

  test("should allow to pass raw strings as input") {
    val is = new StringInputStream("hello")
    val os = new ByteArrayOutputStream()

    new StringPong().handle(is, os, mock[Context])

    os.toString shouldBe """{"outputMsg":"HELLO"}"""
  }

  test("should allow to pass raw strings as output") {
    val is = new StringInputStream("""{ "inputMsg": "HeLLo" }""")
    val os = new ByteArrayOutputStream()

    new PingString().handle(is, os, mock[Context])

    os.toString shouldBe "hello"
  }

  test("should log an error if it has been thrown in the handler") {
    val is = new StringInputStream("""{ "inputMsg": "HeLLo" }""")
    val os = new ByteArrayOutputStream()

    val caught = intercept[Error] {
      new PingPongThrowingAnError().handle(is, os, mock[Context])
    }

    caught.getMessage shouldEqual "PingPongThrowingAnError: Oops"

    val loggingEvent = TestAppender.events.headOption.value
    loggingEvent.getMessage should include("PingPongThrowingAnError: Oops")
    loggingEvent.getLevel shouldBe Level.ERROR
  }

  test("should re-throw an error if the handler has returned Left") {
    val is = new StringInputStream("""{ "inputMsg": "HeLLo" }""")
    val os = new ByteArrayOutputStream()

    val caught = intercept[Error] {
      new PingPongWithError().handle(is, os, mock[Context])
    }

    caught.getMessage shouldEqual "PingPongWithError: Oops"

    // We assume that the error has been logged by the handler itself in this case
    // Hence the log should either be empty, or contain something different
    TestAppender.events.headOption.foreach { loggingEvent =>
      loggingEvent.getMessage should not include "PingPongWithError: Oops"
    }
  }

  test("should support handlers of sequences") {
    val is = new StringInputStream("""["1","42"]""")
    val os = new ByteArrayOutputStream()

    new SeqSeq().handle(is, os, mock[Context])

    os.toString shouldBe """[1,42]"""
    "".reverse
  }

  test("should inject context when overriding the appropriate method") {
    val handler = new Lambda[Int, String] {
      override def handle(input: Int, context: Context): Either[Throwable, String] =
        Right(s"${context.getFunctionName}: $input")
    }

    val is = new StringInputStream("42")
    val os = new ByteArrayOutputStream()

    val contextMock = mock[Context]
    when(contextMock.getFunctionName).thenReturn("testFunction")

    handler.handle(is, os, contextMock)

    os.toString shouldBe "testFunction: 42"
  }

  test("should support None as input and output") {
    val is = new StringInputStream("null")
    val os = new ByteArrayOutputStream()

    new OptionOption().handle(is, os, mock[Context])

    os.toString should be("null")
  }

  test("should support Some as input and output") {
    val is = new StringInputStream("""{ "inputMsg": "HeLLo" }""")
    val os = new ByteArrayOutputStream()

    new OptionOption().handle(is, os, mock[Context])

    os.toString should be("""{"outputMsg":"5"}""")
  }

  test("should support Future as output") {
    val is = new StringInputStream("""{ "inputMsg": "hello" }""")
    val os = new ByteArrayOutputStream()

    val handler =
      Lambda.instance[Ping, Future[Pong]]((ping, _) => Right(Future.successful(Pong(ping.inputMsg.reverse))))
    handler.handle(is, os, mock[Context])

    eventually {
      os.toString shouldBe """{"outputMsg":"olleh"}"""
    }
  }

  test("should support failed Future as output") {
    val is = new StringInputStream("""{ "inputMsg": "hello" }""")
    val os = new ByteArrayOutputStream()

    val handler = Lambda.instance[Ping, Future[Pong]]((_, _) =>
      Right(Future.failed(new IndexOutOfBoundsException("Something is wrong")))
    )

    an[IndexOutOfBoundsException] should be thrownBy handler.handle(is, os, mock[Context])
  }

  test("should fail when Future takes longer than the execution context is ready to provide") {
    import scala.concurrent.ExecutionContext.Implicits.global
    val is = new StringInputStream("""{ "inputMsg": "hello" }""")
    val os = new ByteArrayOutputStream()

    val context = mock[Context]
    when(context.getRemainingTimeInMillis).thenReturn(500 /*ms*/ )

    val handler = Lambda.instance[Ping, Future[Pong]]((_, _) =>
      Right(Future {
        Thread.sleep(1000)
        Pong("Not gonna happen")
      })
    )

    an[TimeoutException] should be thrownBy handler.handle(is, os, context)
  }

  test("should do side effects only") {
    var done    = false
    val is      = new StringInputStream("""null""")
    val os      = new ByteArrayOutputStream()
    val context = mock[Context]

    Lambda
      .instance[None.type, None.type]((_, _) => {
        done = true
        Right(None)
      })
      .handle(is, os, context)

    done shouldBe true
  }
}
