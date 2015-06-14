package ethanp.integration

import java.io.File

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit}
import ethanp.firstVersion._
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers, WordSpecLike}

import scala.concurrent.duration._
import scala.io.Source.fromFile
import scala.language.postfixOps

/**
 * Ethan Petuchowski
 * 6/6/15
 */
class Basics extends FlatSpec with Matchers {

    def filesEqual(path1: String, path2: String): Boolean =
        fromFile(path1).mkString == fromFile(path2).mkString

    def makeActors(num: Int, sys: ActorSystem, props: Props): Vector[ActorRef] =
        (0 until num).map(i => sys.actorOf(props, s"client-$i")).toVector

    def makeClients(num: Int)(implicit sys: ActorSystem): Vector[ActorRef] = makeActors(num, sys, Props[Client])
    def makeTrackers(num: Int)(implicit sys: ActorSystem): Vector[ActorRef] = makeActors(num, sys, Props[Tracker])

    "A downloaded file" should "have the same contents" in {
        implicit val sys: ActorSystem = ActorSystem("as_if")
        val tracker = sys.actorOf(Props[Tracker], "tracker-0")
        val clients = makeClients(3)
        val filename = "Test1.txt"
        val fromDir = "testfiles"
        val toDir = "downloads"
        val fromLoc = s"$fromDir/$filename"
        val toLoc = s"$toDir/$filename"
        new File(toLoc).delete()
        clients.foreach(_ ! TrackerLoc(tracker))
        clients.take(2).foreach(_ ! LoadFile(fromLoc, filename))
        Thread sleep 100
        clients(2) ! DownloadFileFrom(tracker, filename)
        Thread sleep 150
        println("testing now")
        assert(filesEqual(fromLoc, toLoc))
    }
}

class MySpec(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
with WordSpecLike with Matchers with BeforeAndAfterAll {

    def this() = this(ActorSystem("MySpec"))
    def filesEqual(path1: String, path2: String): Boolean = fromFile(path1).mkString == fromFile(path2).mkString
    override def afterAll() {
        TestKit.shutdownActorSystem(system)
    }

    val filename = "Test2.txt"
    val fromDir = "testfiles"
    val toDir = "downloads"
    val fromLoc = s"$fromDir/$filename"
    val toLoc = s"$toDir/$filename"
    new File(toLoc).delete()
    "A client actor" must {

        "upload large file to tracker" in {
            val tracker = system.actorOf(Props[Tracker])
            val client = system.actorOf(Props[Client])
            client ! TrackerLoc(tracker)
            within(30 seconds) {
                client ! LoadFile(fromLoc, filename)
                expectMsg(SuccessfullyAdded(filename))
            }
            expectNoMsg(1 second)
        }
        "download large file from peer" in {
            val tracker = system.actorOf(Props[Tracker])
            val client = system.actorOf(Props[Client])
            val client2 = system.actorOf(Props[Client])
            client ! TrackerLoc(tracker)
            client2 ! TrackerLoc(tracker)
            within(30 seconds) {
                client ! LoadFile(fromLoc, filename)
                expectMsg(SuccessfullyAdded(filename))
            }
            within(30 seconds) {
                client2 ! DownloadFile(filename)
                expectMsg(DownloadSuccess(filename))
            }
            assert(filesEqual(fromLoc, toLoc))
        }
    }
}
