package ethanp.integration

import java.io.File

import akka.actor.{Actor, ActorRef, ActorSystem}
import akka.testkit.{DefaultTimeout, ImplicitSender, TestActorRef, TestKit}
import com.typesafe.config.ConfigFactory
import ethanp.file.{FileToDownload, FileInfo, LocalP2PFile, Sha2}
import ethanp.firstVersion.Master.NodeID
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

    val c2: NodeID = 2
    val c3: NodeID = 3
    val (testText, testTextLoc) = "test" -> "testfiles/Test1.txt"
    val (inputText, inputTextLoc) = "test2" -> "testfiles/input2.txt"
    val testTextP2P = LocalP2PFile.loadFile(testText, testTextLoc)
    val inputTextP2P = LocalP2PFile.loadFile(inputText, inputTextLoc)

    override def afterAll() { shutdown() }

    "A LocalP2PFile" should {
        "look as follows" in {
            testTextP2P should equal (LocalP2PFile(FileInfo(
                filename = testText,
                chunkHashes = Vector(Sha2("fND18YuOoWW8VoyGYs0sIVGXbaneeTGKPXVpgNLd9zQ=")),
                fileLength = 53),
                file = new File(testTextLoc))
            )
        }
    }

    "A Tracker" should {

        val knowledge1 = FileToDownload(
            testTextP2P.fileInfo,
            seeders = Map(c2 → self),
            leechers = Map())

        val knowledge2 = FileToDownload(
            testTextP2P.fileInfo,
            seeders = Map(c2 → self, c3 → self),
            leechers = Map())

        val knowledge3 = FileToDownload(
            inputTextP2P.fileInfo,
            seeders = Map(c3 → self),
            leechers = Map())

        "successfully add a new file with a seeder" in {
            within(500 millis) {
                trackerRef ! InformTrackerIHave(c2, testTextP2P.fileInfo)
                expectMsg(SuccessfullyAdded(testTextP2P.fileInfo.filename))
            }
            tracker knowledgeOf testText should equal (knowledge1)
        }
        "fail when trying to add a duplicate file" in {
            within(500 millis) {
                trackerRef ! InformTrackerIHave(c2, testTextP2P.fileInfo)
                expectMsg(TrackerSideError("already knew you are seeding this file"))
            }
            tracker knowledgeOf testText should equal (knowledge1)
        }
        "successfully add a second seeder" in {
            within(500 millis) {
                trackerRef ! InformTrackerIHave(c3, testTextP2P.fileInfo)
                expectMsg(SuccessfullyAdded(testTextP2P.fileInfo.filename))
            }
            tracker knowledgeOf testText should equal (knowledge2)
        }
        "successfully add a second file" in {
            within(500 millis) {
                trackerRef ! InformTrackerIHave(c3, inputTextP2P.fileInfo)
                expectMsg(SuccessfullyAdded(inputTextP2P.fileInfo.filename))
            }
            tracker knowledgeOf testText should equal (knowledge2)
            tracker knowledgeOf inputText should equal (knowledge3)
        }
        "fail a download request for an unknown file" in {
            // TODO not passing
            within(500 millis) {
                trackerRef ! DownloadFile(c2, "UNKNOWN_FILE")
                expectMsg(TrackerSideError(s"I don't know a file called UNKNOWN_FILE"))
            }
            assert(true)
        }
        "succeed a download request for an known file" ignore {
            within(500 millis) {
                trackerRef ! DownloadFile(c2, inputText)
                expectMsg(TrackerSideError(s"I don't know a file called UNKNOWN_FILE"))
            }
            assert(true)
        }
    }

    "a Client" should {
        "load a file" ignore {

        }
        "inform all trackers of loaded file" ignore {

        }
        "add a tracker loc" ignore {

        }
        "list a tracker" ignore {

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
