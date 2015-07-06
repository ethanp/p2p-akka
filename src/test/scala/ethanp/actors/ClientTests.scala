package ethanp.actors

import akka.testkit.TestActorRef
import ethanp.firstVersion._

import scala.concurrent.duration._
import scala.language.postfixOps

/**
 * Ethan Petuchowski
 * 6/14/15
 */
class ClientTests extends BaseTester {
    val clientRef = TestActorRef[Client]
    val client = clientRef.underlyingActor

    "gleaning info from trackers" should {
        "learn about trackers" in {
            within(100 millis) {
                bouncers.foreach(clientRef ! TrackerLoc(_))
                expectNoMsg(/* leave time for state update */)
            }
            client.knownTrackers should have size 2
        }
        "load file and inform all trackers" in {
            quickly {
                clientRef ! LoadFile(testTextLoc, testText)
                val iHave = InformTrackerIHave(testTextP2P.fileInfo)
                expectMsgAllOf(iHave, iHave)
            }
        }
        "list a tracker" in {
            quickly {
                clientRef ! ListTracker(self)
                expectMsg(ListTracker(self))
            }
        }
    }
}
