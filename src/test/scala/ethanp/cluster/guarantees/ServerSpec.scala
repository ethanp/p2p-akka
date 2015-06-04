package ethanp.cluster.guarantees

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestActorRef, TestKit}
import ethanp.cluster._
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

/**
 * Ethan Petuchowski
 * 4/13/15
 */
class SystemSpec(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
  with WordSpecLike with Matchers with BeforeAndAfterAll {

  def this() = this(ActorSystem("ClusterSystem"))
  override def afterAll { TestKit shutdownActorSystem system }

  // here we obtain an actual "Server" object
  val masterRef = TestActorRef[Master]
  val masterPtr = masterRef.underlyingActor
  val primaryName: AcceptStamp = AcceptStamp(null, 1)

  // now we can do normal unit testing with the `actor`
  // but also, messages sent to the `actorRef` are processed *synchronously*
  //      on the current thread and answers may be sent back as usual.

  /* TODO I think I could get the from -> to print to work
    if you look at what happens on an "IExist() reception in the server,
    this is what I have to look-up in the map!
   */
}

class TryIt extends SystemSpec() {

  /* setup initial state */

  "a server receiving these updates" must {


    "update its csn" in {
    }
    "add the writes to its writeLog" in {
    }
    "update its VV" in {
    }
  }
}

//class CommitUncommittedUponReceipt extends SystemSpec() {
//  /* setup initial state */
//  val sName = AcceptStamp(1, primaryName)
//  val aPut = Put(3, "a", "123")
//  val writeMeIn = Write(1, sName, CreationWrite)
//  masterPtr.serverName = sName
//  masterPtr.myVersionVector = MutableVV(sName → 1, primaryName → 1)
//  masterPtr.writeLog += writeMeIn
//  masterPtr.writeLog += Write(Integer.MAX_VALUE, sName, aPut)
//  masterPtr.csn = 1
//
//  "a server receiving these updates" must {
//
//    val committedPut = Write(2, sName, aPut)
//    masterRef ! UpdateWrites(TreeSet(committedPut))
//
//    "update its csn" in {
//      assert(masterPtr.csn == 2)
//    }
//
//    "have the following log" in {
//      assert(masterPtr.writeLog.toList == List(writeMeIn, committedPut))
//    }
//  }
//}
