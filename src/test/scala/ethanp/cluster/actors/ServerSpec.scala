package ethanp.cluster.actors

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestActorRef, TestKit}
import ethanp.cluster._
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

import scala.collection.immutable.TreeSet

/**
 * Ethan Petuchowski
 * 4/13/15
 */
class ServerSpec(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
  with WordSpecLike with Matchers with BeforeAndAfterAll {

  def this() = this(ActorSystem("ClusterSystem"))
  override def afterAll { TestKit shutdownActorSystem system }

  // here we obtain an actual "Server" object
  val serverRef = TestActorRef[Tracker]
  val serverPtr = serverRef.underlyingActor
  val primaryName: AcceptStamp = AcceptStamp(null, 0)

  // now we can do normal unit testing with the `actor`
  // but also, messages sent to the `actorRef` are processed *synchronously*
  //      on the current thread and answers may be sent back as usual.

  /* TODO I think I could get the from -> to print to work
    if you look at what happens on an "IExist() reception in the server,
    this is what I have to look-up in the map!
   */
}

class VVUpdates extends ServerSpec() {

  /* setup initial state */
  val sName = AcceptStamp(primaryName, 1)
  serverPtr.serverName = sName
  serverPtr.myVersionVector = MutableVV(sName → 1, primaryName → 1)
  serverPtr.writeLog += Write(1, sName, CreationWrite)
  serverPtr.csn = 1

  "a server receiving these updates" must {

    val writeCreateTwo = Write(2, AcceptStamp(AcceptStamp(null, 0), 2), CreationWrite)
    val writePutA = Write(3, AcceptStamp(AcceptStamp(null, 0), 3), Put(3, "a", "123"))
    serverRef ! UpdateWrites(TreeSet(writeCreateTwo,writePutA))

    "update its csn" in {
      assert(serverPtr.csn == 3)
    }
    "add the writes to its writeLog" in {
      assert(serverPtr.writeLog contains writeCreateTwo)
      assert(serverPtr.writeLog contains writePutA)
    }
    "update its VV" in {
      assert(serverPtr.myVersionVector(sName) == 1)
      assert(serverPtr.myVersionVector.size == 3)
    }
  }
}

class CommitUncommittedUponReceipt extends ServerSpec() {
  /* setup initial state */
  val sName = AcceptStamp(primaryName, 1)
  val aPut = Put(3, "a", "123")
  val writeMeIn = Write(1, sName, CreationWrite)
  serverPtr.serverName = sName
  serverPtr.myVersionVector = MutableVV(sName → 1, primaryName → 1)
  serverPtr.writeLog += writeMeIn
  serverPtr.writeLog += Write(Integer.MAX_VALUE, sName, aPut)
  serverPtr.csn = 1

  "a server receiving these updates" must {

    val committedPut = Write(2, sName, aPut)
    serverRef ! UpdateWrites(TreeSet(committedPut))

    "update its csn" in {
      assert(serverPtr.csn == 2)
    }

    "have the following log" in {
      assert(serverPtr.writeLog.toList == List(writeMeIn, committedPut))
    }
  }
}
