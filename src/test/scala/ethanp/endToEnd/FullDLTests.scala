package ethanp.endToEnd

import java.io.File

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.TestActorRef
import ethanp.actors.BaseTester
import ethanp.firstVersion._

import scala.io.Source._

/**
 * Created by Ethan Petuchowski on 7/6/15.
 *
 */
class DLTests extends BaseTester {
    def filesEqual(path1: String, path2: String): Boolean =
        fromFile(path1).mkString == fromFile(path2).mkString

    def makeActors(num: Int, props: Props, name: String)(implicit sys: ActorSystem):
    Vector[ActorRef] = (0 until num).map(i => sys.actorOf(props, s"$name-$i")).toVector

    def makeClients(num: Int) = makeActors(num, Props[Client], "client")
    def makeTrackers(num: Int) = makeActors(num, Props[Tracker], "tracker")
    val fromDir = "testfiles"
    val toDir = "downloads"
    def fromTo(filename: String) = s"$fromDir/$filename" -> s"$toDir/$filename"
}
class FullDLTests extends DLTests {

    // TODO the goal here is to PROPERLY set thing up
    // so that we're not configuring the state of the system with API calls
    // we should instead be DIRECTLY setting the state

    "A downloaded file" should {
        "have the same contents" in {
            val tracker = TestActorRef(Props[Tracker], "tracker-0")
            val trkPtr: Tracker = tracker.underlyingActor
            trkPtr.
            val clients = makeClients(3)
            val filename = "Test1.txt"
            val (from, to) = fromTo(filename)
            new File(to).delete()

            // tell clients about tracker
            clients foreach (_ ! TrackerLoc(tracker))

            // 2 clients tell tracker they have the file
            clients take 2 foreach (_ ! LoadFile(from, filename))
            Thread sleep 100

            // remaining client downloads file
            clients(2) ! DownloadFileFrom(tracker, filename)
            Thread sleep 150

            // see if it worked
            assert(filesEqual(from, to))
        }
    }

    "downloading a file" should {
        val dir = new File("downloads")
        if (dir.exists()) dir.delete()

        val client = TestActorRef(Props[Client])

//        client !

        "create the download directory" in {
            dir should exist
        }
//        "create the file" ignore {}
//        "eventually own the full file" ignore {}
    }
}
