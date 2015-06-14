package ethanp.integration

import akka.actor.{Actor, ActorRef, ActorSystem}
import akka.testkit.{DefaultTimeout, ImplicitSender, TestKit}
import com.typesafe.config.ConfigFactory
import ethanp.file.LocalP2PFile
import org.scalatest.{BeforeAndAfterAll, Inside, Matchers, WordSpecLike}

/**
 * Ethan Petuchowski
 * 6/14/15
 *
 * based on template at http://doc.akka.io/docs/akka/snapshot/scala/testkit-example.html
 */

class BaseTester extends TestKit(ActorSystem("InitialFuncs", ConfigFactory.parseString(BaseTester.config)))
with DefaultTimeout with ImplicitSender with WordSpecLike with Matchers with BeforeAndAfterAll with Inside
{
    val (testText, testTextLoc) = "test" -> "testfiles/Test1.txt"
    val (inputText, inputTextLoc) = "test2" -> "testfiles/input2.txt"
    val testTextP2P = LocalP2PFile.loadFile(testText, testTextLoc)
    val inputTextP2P = LocalP2PFile.loadFile(inputText, inputTextLoc)
    override def afterAll() { shutdown() }
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
