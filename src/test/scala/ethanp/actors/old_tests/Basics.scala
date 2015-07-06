package ethanp.actors.old_tests

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
 *
 * The tests in this file probably won't work anymore. I have a new protocol now.
 */
class Basics extends FlatSpec with Matchers {

    implicit val sys = ActorSystem("as_if")

    def filesEqual(path1: String, path2: String): Boolean =
        fromFile(path1).mkString == fromFile(path2).mkString

    def makeActors(num: Int, props: Props, name: String)(implicit sys: ActorSystem):
    Vector[ActorRef] = (0 until num).map(i => sys.actorOf(props, s"$name-$i")).toVector

    def makeClients(num: Int) = makeActors(num, Props[Client], "client")
    def makeTrackers(num: Int) = makeActors(num, Props[Tracker], "tracker")

    "A downloaded file" should "have the same contents" in {
        val tracker = sys.actorOf(Props[Tracker], "tracker-0")
        val clients = makeClients(3)
        val filename = "Test1.txt"
        val fromDir = "testfiles"
        val toDir = "downloads"
        val fromLoc = s"$fromDir/$filename"
        val toLoc = s"$toDir/$filename"
        new File(toLoc).delete()

        // tell clients about tracker
        clients foreach (_ ! TrackerLoc(tracker))

        // 2 clients tell tracker they have the file
        clients take 2 foreach (_ ! LoadFile(fromLoc, filename))
        Thread sleep 100

        // remaining client downloads file
        clients(2) ! DownloadFileFrom(tracker, filename)
        Thread sleep 150

        // see if it worked
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
            within(1 second) {
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
