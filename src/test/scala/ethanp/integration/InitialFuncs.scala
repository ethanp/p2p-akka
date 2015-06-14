package ethanp.integration

import java.io.File

import akka.actor._
import akka.testkit.TestActorRef
import ethanp.file.{FileInfo, FileToDownload, LocalP2PFile, Sha2}
import ethanp.firstVersion._
import ethanp.integration.BaseTester.ForwardingActor

import scala.concurrent.duration._
import scala.language.postfixOps

/**
 * Ethan Petuchowski
 * 6/11/15
 */
class InitialFuncs extends BaseTester {
    val bouncers = (1 to 2).map(i => system.actorOf(Props(classOf[ForwardingActor], self)))
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

        val knowledge1 = FileToDownload(
            testTextP2P.fileInfo,
            seeders = Set(bouncers.head),
            leechers = Set())

        val knowledge2 = knowledge1.seed_+(bouncers.last)

        val knowledge3 = FileToDownload(
            inputTextP2P.fileInfo,
            seeders = Set(bouncers.last),
            leechers = Set())

        val knowledge4 = knowledge3.leech_+(bouncers.head)

        "successfully add a new file with a seeder" in {
            within(500 millis) {
                trackerRef.tell(InformTrackerIHave(testTextP2P.fileInfo), bouncers.head)
                expectMsg(SuccessfullyAdded(testTextP2P.fileInfo.filename))
            }
            tracker knowledgeOf testText should equal (knowledge1)
        }
        "fail when trying to add a duplicate file" in {
            within(500 millis) {
                trackerRef.tell(InformTrackerIHave(testTextP2P.fileInfo), bouncers.head)
                expectMsg(TrackerSideError("already knew you are seeding this file"))
            }
            tracker knowledgeOf testText should equal (knowledge1)
        }
        "successfully add a second seeder" in {
            within(500 millis) {
                trackerRef.tell(InformTrackerIHave(testTextP2P.fileInfo), bouncers.last)
                expectMsg(SuccessfullyAdded(testTextP2P.fileInfo.filename))
            }
            tracker knowledgeOf testText should equal (knowledge2)
        }
        "successfully add a second file" in {
            within(500 millis) {
                trackerRef.tell(InformTrackerIHave(inputTextP2P.fileInfo), bouncers.last)
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
            assert(true) // intellij needs this for its ego
        }
        "succeed a download request for an known file" in {
            within(500 millis) {
                trackerRef.tell(DownloadFile(inputText), bouncers.head)
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
                within(100 millis) {
                    bouncers.foreach(clientRef ! TrackerLoc(_))
                    expectNoMsg(/* give it time for state update */)
                }
                client.knownTrackers should have size 2
            }
            "load file and inform all trackers" in {
                within(500 millis) {
                    clientRef ! LoadFile(testTextLoc, testText)
                    val iHave = InformTrackerIHave(testTextP2P.fileInfo)
                    expectMsgAllOf(iHave, iHave)
                }
                assert(true)
            }
            "list a tracker" in {
                within(500 millis) {
                    clientRef ! ListTracker(self)
                    expectMsg(ListTracker(self))
                }
                assert(true)
            }
        }
    }
}
