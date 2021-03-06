package ethanp.actors

import akka.testkit.TestActorRef
import ethanp.backend.Client
import ethanp.backend.client._

import scala.concurrent.duration._
import scala.language.postfixOps

/**
  * Ethan Petuchowski
  * 6/14/15
  */
class ClientTests extends BaseTester {
    val clientRef = TestActorRef(Client.props).asInstanceOf[TestActorRef[Client]]
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
                clientRef ! LoadFile(test1TextLoc, test1)
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
