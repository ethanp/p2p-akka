package ethanp.integration

import java.io.File

import akka.actor.{Actor, ActorRef, ActorSystem}
import akka.testkit.{DefaultTimeout, ImplicitSender, TestActorRef, TestKit}
import com.typesafe.config.ConfigFactory
import ethanp.file.{FileToDownload, FileInfo, LocalP2PFile, Sha2}
import ethanp.firstVersion._
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

import scala.collection.immutable
import scala.concurrent.duration._
import scala.language.postfixOps

/**
 * Ethan Petuchowski
 * 6/11/15
 *
 * based on template at http://doc.akka.io/docs/akka/snapshot/scala/testkit-example.html
 */
class InitialFuncs extends TestKit(ActorSystem("InitialFuncs", ConfigFactory.parseString(InitialFuncs.config)))
with DefaultTimeout with ImplicitSender with WordSpecLike with Matchers with BeforeAndAfterAll {

    val trackerRef = TestActorRef[Tracker]
    val clientRef = TestActorRef[Client]

    val tracker = trackerRef.underlyingActor
    val client = clientRef.underlyingActor

    val (name, loc) = "test" -> "testfiles/Test1.txt"
    val localP2PFile = LocalP2PFile.loadFile(name, loc)

    override def afterAll() { shutdown() }

    "A LocalP2PFile" should {
        "look as follows" in {
            localP2PFile should equal (LocalP2PFile(FileInfo(
                filename = name,
                chunkHashes = Vector(Sha2("fND18YuOoWW8VoyGYs0sIVGXbaneeTGKPXVpgNLd9zQ=")),
                fileLength = 53),
                file = new File(loc))
            )
        }
    }

    "A Tracker" should {
        "successfully add a new file with a seeder" in {
            within(500 millis) {
                trackerRef ! InformTrackerIHave(2, localP2PFile.fileInfo)
                expectMsg(SuccessfullyAdded(localP2PFile.fileInfo.filename))
            }
            tracker.myKnowledge(name) should equal (FileToDownload(
                localP2PFile.fileInfo, Swarm(Map(2 -> self), Map())))
        }
        "fail when trying to add a duplicate file" in {
            within(500 millis) {
                trackerRef ! InformTrackerIHave(2, localP2PFile.fileInfo)
                expectMsg(TrackerSideError("already knew you are seeding this file"))
            }
            tracker.myKnowledge(name) should equal (FileToDownload(
                localP2PFile.fileInfo, Swarm(Map(2 -> self), Map())))
        }
    }
}

object InitialFuncs {
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
    next: ActorRef, head: immutable.Seq[String],
    tail: immutable.Seq[String]) extends Actor {
        def receive = {
            case msg => {
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

}
