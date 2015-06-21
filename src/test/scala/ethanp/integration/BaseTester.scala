package ethanp.integration

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit}
import com.typesafe.config.ConfigFactory
import ethanp.file.LocalP2PFile
import ethanp.integration.BaseTester.ForwardingActor
import org.scalatest.{BeforeAndAfterAll, Inside, Matchers, WordSpecLike}

import scala.concurrent.duration._
import scala.language.postfixOps

/**
 * Ethan Petuchowski
 * 6/14/15
 *
 * based on template at http://doc.akka.io/docs/akka/snapshot/scala/testkit-example.html
 */

class BaseTester
extends TestKit(ActorSystem("InitialFuncs", ConfigFactory.parseString(BaseTester.config)))
with ImplicitSender // allows you to use `self` as an actor
with WordSpecLike   // "an x" when { "condition" should { "do something" in { assertions } } }
with Matchers       //  e.g. shouldBe, have length, startWith, include, etc.
with BeforeAndAfterAll // allow beforeAll and afterAll methods
with Inside         // allows `inside (caseClass) { case CaseClass(abcd) => abcd shouldBe x }`
{
    override def afterAll() { shutdown() }
    val (testText, testTextLoc) = "test" -> "testfiles/Test1.txt"
    val (inputText, inputTextLoc) = "test2" -> "testfiles/input2.txt"
    val testTextP2P = LocalP2PFile.loadFile(testText, testTextLoc)
    val inputTextP2P = LocalP2PFile.loadFile(inputText, inputTextLoc)
    val bouncers = (1 to 2).map(i => system.actorOf(Props(classOf[ForwardingActor], self)))
    implicit val duration = 300 millis
    def waitOnA[T](implicit duration: Duration) = receiveOne(duration).asInstanceOf[T]
    def quickly[T](f: => T): Unit = { within[T](duration)(f); assert(true) /*for highlighting*/ }
    def splitAtIndex[U <: Iterable[ActorRef]](seq: U, n: Int): (U, U) = (seq take n).asInstanceOf[U] → (seq drop n).asInstanceOf[U]
    def expectNOf[T](n: Int, t: T): Unit = for (i ← 1 to n) expectMsg(t)
}

object BaseTester {
    // Define your test specific configuration here
    val config = """
    akka {
      loglevel = "WARNING"
    }
                 """

    /**
     * An Actor that forwards every message to a next Actor
     */
    class ForwardingActor(next: ActorRef) extends Actor {
        def receive = {
            case msg => next ! msg
        }
    }

    /**
     * An Actor that only forwards certain messages to a next Actor
     */
    class FilteringActor(next: ActorRef) extends Actor {
        def receive = {
            case msg: String => next ! msg
            case _ => None
        }
    }

    /**
     * An actor that sends a sequence of messages with a random head list, an
     * interesting value and a random tail list. The idea is that you would
     * like to test that the interesting value is received and that you cant
     * be bothered with the rest
     */
    class SequencingActor(
    next: ActorRef, head: Seq[String],
    tail: Seq[String]) extends Actor {
        def receive = {
            case msg =>
                head foreach {
                    next ! _
                }
                next ! msg
                tail foreach {
                    next ! _
                }
        }
    }
}
