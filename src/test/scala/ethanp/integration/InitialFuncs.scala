package ethanp.integration

import java.io.File

import akka.actor._
import akka.testkit.{DefaultTimeout, ImplicitSender, TestActorRef, TestKit}
import com.typesafe.config.ConfigFactory
import ethanp.file.{FileToDownload, FileInfo, LocalP2PFile, Sha2}
import ethanp.firstVersion.Master.NodeID
import ethanp.firstVersion._
import ethanp.integration.InitialFuncs.ForwardingActor
import org.scalatest.{Inside, BeforeAndAfterAll, Matchers, WordSpecLike}

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
with DefaultTimeout with ImplicitSender with WordSpecLike with Matchers with BeforeAndAfterAll with Inside {

    val c2: NodeID = 2
    val c3: NodeID = 3
    val (testText, testTextLoc) = "test" -> "testfiles/Test1.txt"
    val (inputText, inputTextLoc) = "test2" -> "testfiles/input2.txt"
    val testTextP2P = LocalP2PFile.loadFile(testText, testTextLoc)
    val inputTextP2P = LocalP2PFile.loadFile(inputText, inputTextLoc)

    val bouncers = (1 to 2).map(i => system.actorOf(Props(classOf[ForwardingActor], i, self)))

    val knowledge1 = FileToDownload(
        testTextP2P.fileInfo,
        seeders = Set(bouncers.head),
        leechers = Set())

    val knowledge2 = FileToDownload(
        testTextP2P.fileInfo,
        seeders = bouncers.toSet,
        leechers = Set())

    val knowledge3 = FileToDownload(
        inputTextP2P.fileInfo,
        seeders = Set(bouncers.last),
        leechers = Set())

    val knowledge4 = FileToDownload(
        inputTextP2P.fileInfo,
        seeders = Set(bouncers.last),
        leechers = Set(bouncers.head))

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

    // seems like `with DefaultTimeout` had no effect?
    implicit val duration = 500 millis
    def waitOnA[T](implicit duration: Duration) = receiveOne(duration).asInstanceOf[T]

    "A Tracker" should {

        val trackerRef = TestActorRef[Tracker]
        val tracker = trackerRef.underlyingActor

        "successfully add a new file with a seeder" in {
            within(500 millis) {
                trackerRef ! InformTrackerIHave(testTextP2P.fileInfo)
                expectMsg(SuccessfullyAdded(testTextP2P.fileInfo.filename))
            }
            tracker knowledgeOf testText should equal (knowledge1)
        }
        "fail when trying to add a duplicate file" in {
            within(500 millis) {
                trackerRef ! InformTrackerIHave(testTextP2P.fileInfo)
                expectMsg(TrackerSideError("already knew you are seeding this file"))
            }
            tracker knowledgeOf testText should equal (knowledge1)
        }
        "successfully add a second seeder" in {
            within(500 millis) {
                trackerRef ! InformTrackerIHave(testTextP2P.fileInfo)
                expectMsg(SuccessfullyAdded(testTextP2P.fileInfo.filename))
            }
            tracker knowledgeOf testText should equal (knowledge2)
        }
        "successfully add a second file" in {
            within(500 millis) {
                trackerRef ! InformTrackerIHave(inputTextP2P.fileInfo)
                expectMsg(SuccessfullyAdded(inputTextP2P.fileInfo.filename))
            }
            tracker knowledgeOf testText should equal (knowledge2)
            tracker knowledgeOf inputText should equal (knowledge3)
        }
        "fail a download request for an unknown file" in {
            within(500 millis) {
                trackerRef ! DownloadFile("UNKNOWN_FILE")
                expectMsg(TrackerSideError(s"I don't know a file called UNKNOWN_FILE"))
            }
            expectNoMsg()
        }
        "succeed a download request for an known file" in {
            within(500 millis) {
                trackerRef ! DownloadFile(inputText)
                expectMsg(knowledge3)
            }
            tracker knowledgeOf inputText shouldBe knowledge4
        }
        // change yo stao up, switch to southpaw
        "return its knowledge upon request" in {
            trackerRef ! ListTracker(self)
            val trackerK = waitOnA[TrackerKnowledge]
            inside (trackerK) { case TrackerKnowledge(knowledge) =>
                knowledge should contain only (knowledge2, knowledge4)
            }
        }
    }

    "a Client" when {
        val clientRef = TestActorRef[Client]
        val client = clientRef.underlyingActor

        "gleaning info from trackers" should {
            "learn about trackers" in {
                within(500 millis) {
                    clientRef ! TrackerLoc(self)
                    clientRef ! TrackerLoc(self)
                    expectNoMsg()
                }
                client.knownTrackers should have size 2
            }
            "load file and inform all trackers" in {
                within(500 millis) {
                    clientRef ! LoadFile(testTextLoc, testText)
                    val iHave = InformTrackerIHave(testTextP2P.fileInfo)
                    expectMsgAllOf(iHave, iHave)
                }
                expectNoMsg()
            }
            "list a tracker" in {
                within(500 millis) {
                    clientRef ! ListTracker(self)
                    expectMsg(ListTracker(self))
                }
                expectNoMsg()
            }
        }
    }

    /* TODO how can I make writing these tests get me to the END goal FASTER?
     * with more focus on speed and less focus on absolute robustness
     * TODO I need to keep tests at a higher level
     * this will lead to easier refactoring in these beginning stages anyway
     */
    "a FileDownloader" when {
        "there are 5 seeders and 5 leechers" when {
            val fwdActors = (1 to 10).map(i => system.actorOf(Props(classOf[ForwardingActor], i, self)))
            val seeders = (fwdActors take 5).toSet
            val leechers = (fwdActors drop 5).toSet
            val ftd = FileToDownload(testTextP2P.fileInfo, seeders, leechers)
            val liveSeeders = seeders take 3
            val deadSeeders = seeders drop 3
            val liveLeechers = leechers take 2
            val deadLeechers = leechers drop 2
            val dlDir = new File("test_downloads")
            dlDir.deleteOnExit()
            val fileDLRef = TestActorRef(new FileDownloader(ftd, dlDir))
            "first starting up" should {
                "check which peers are alive" when {
                    "2 seeders and 3 leechers are down" ignore {

                    }
                }
            }
            "set aside peers who don't respond" ignore {
                // look at internal state
            }
            "" ignore {}
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
    class ForwardingActor(id: Int, next: ActorRef) extends Actor {
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
