package ethanp.actors

import akka.actor._
import akka.testkit.{ImplicitSender, TestKit}
import com.typesafe.config.ConfigFactory
import ethanp.actors.BaseTester.ForwardingActor
import ethanp.file.LocalP2PFile
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
    with WordSpecLike // "an x" when { "condition" should { "do something" in { assertions } } }
    with Matchers //  e.g. shouldBe, have length, startWith, include, etc.
    with BeforeAndAfterAll // allow beforeAll and afterAll methods
    with Inside /* allows `inside (caseClass) { case CaseClass(abcd) => abcd shouldBe x }` */ {
    val (test1, test1TextLoc) = "test1" → "testfiles/Test1.txt"
    val (test2, input2TextLoc) = "test2" → "testfiles/input2.txt"
    val testTextP2P = LocalP2PFile.loadFile(test1, test1TextLoc)
    val input2TextP2P = LocalP2PFile.loadFile(test2, input2TextLoc)
    val bouncers = (1 to 2).map(i => system.actorOf(Props(classOf[ForwardingActor], self)))
    implicit val duration = 300 millis

    override def afterAll() { shutdown() }

    def waitOnA[T](implicit duration: Duration) = receiveOne(duration).asInstanceOf[T]

    def splitAtIndex[U <: Iterable[ActorRef]](seq: U, n: Int): (U, U) = (seq take n).asInstanceOf[U] → (seq drop n).asInstanceOf[U]

    def expectNOf[T](n: Int, t: T): Unit = for (i ← 1 to n) expectMsg(t)

    def expectSoon[T](t: T): Unit = quickly(expectMsg(t))

    def quickly[T](f: ⇒ T): Unit = { within[T](duration)(f); assert(true) /*for highlighting*/ }
}

object BaseTester {
    // Define your test specific configuration here
    val config =
        """
        akka {
          loglevel = "WARNING"
        }
        """

    case class OrigAddressee(m: Any, actorPath: ActorPath)

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

}
