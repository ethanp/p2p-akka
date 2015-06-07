package ethanp.integration

import akka.actor.{ActorSystem, Props}
import ethanp.firstVersion._
import org.scalatest.{FlatSpec, Matchers}

import scala.io.Source.fromFile

/**
 * Ethan Petuchowski
 * 6/6/15
 */
class Basics extends FlatSpec with Matchers {

    def filesEqual(path1: String, path2: String): Boolean =
        fromFile(path1).mkString == fromFile(path2).mkString

    "A downloaded file" should "have the same hash value" in {
        val sys: ActorSystem = ActorSystem("as_if")
        val tracker = sys.actorOf(Props[Tracker], "tracker-0")
        val clients = List(
            sys.actorOf(Props[Client], "client-0"),
            sys.actorOf(Props[Client], "client-1"),
            sys.actorOf(Props[Client], "client-2")
        )
        val filename = "Test1.txt"
        val fromDir = "testfiles"
        val toDir = "downloads"
        val fromLoc = s"$fromDir/$filename"
        val toLoc = s"$toDir/$filename"
        clients.foreach(_ ! TrackerLoc(0, tracker))
        clients.take(2).foreach(_ ! LoadFile(fromLoc, filename))
        Thread sleep 100
        clients(2) ! DownloadFile(0, filename)
        Thread sleep 150
        println("testing now")
        assert(filesEqual(fromLoc, toLoc))
    }
}
