package ethanp.actors

import akka.testkit.TestActorRef
import ethanp.backend.client._
import ethanp.file.FileToDownload

import scala.language.postfixOps

/**
  * Ethan Petuchowski
  * 6/11/15
  */
class TrackerTests extends BaseTester {

    // using ScalaTest's "must" would be semantically equivalent
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
            quickly {
                trackerRef.tell(InformTrackerIHave(testTextP2P.fileInfo), bouncers.head)
                expectMsg(SuccessfullyAdded(testTextP2P.fileInfo.filename))
            }
            tracker knowledgeOf testText should equal(knowledge1)
        }
        "fail when trying to add a duplicate file" in {
            quickly {
                trackerRef.tell(InformTrackerIHave(testTextP2P.fileInfo), bouncers.head)
                expectMsg(TrackerSideError("already knew you are seeding this file"))
            }
            tracker knowledgeOf testText should equal(knowledge1)
        }
        "successfully add a second seeder" in {
            quickly {
                trackerRef.tell(InformTrackerIHave(testTextP2P.fileInfo), bouncers.last)
                expectMsg(SuccessfullyAdded(testTextP2P.fileInfo.filename))
            }
            tracker knowledgeOf testText should equal(knowledge2)
        }
        "successfully add a second file" in {
            quickly {
                trackerRef.tell(InformTrackerIHave(inputTextP2P.fileInfo), bouncers.last)
                expectMsg(SuccessfullyAdded(inputTextP2P.fileInfo.filename))
            }
            tracker knowledgeOf testText should equal(knowledge2)
            tracker knowledgeOf inputText should equal(knowledge3)
        }
        "fail a download request for an unknown file" in {
            quickly {
                trackerRef ! DownloadFile("UNKNOWN_FILE")
                expectMsg(TrackerSideError(s"I don't know a file called UNKNOWN_FILE"))
            }
        }
        "succeed a download request for an known file" in {
            quickly {
                trackerRef.tell(DownloadFile(inputText), bouncers.head)
                expectMsg(knowledge3)
            }
            tracker knowledgeOf inputText shouldBe knowledge4
        }
        // change yo stao up, switch to southpaw
        "return its knowledge upon request" in {
            trackerRef ! ListTracker(self)
            val trackerK = waitOnA[TrackerKnowledge]
            inside(trackerK) { case TrackerKnowledge(knowledge) =>
                knowledge should contain only(knowledge2, knowledge4)
            }
        }
    }
}
