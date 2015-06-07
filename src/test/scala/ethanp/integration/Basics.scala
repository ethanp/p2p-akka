package ethanp.integration

import akka.actor.{ActorRef, ActorSystem, Props}
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

    def makeActors(num: Int, sys: ActorSystem, props: Props): Vector[ActorRef] =
        (0 until num).map(i ⇒ sys.actorOf(props, s"client-$i")).toVector

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
        clients.foreach(_ ! TrackerLoc(0, tracker))
        clients.take(2).foreach(_ ! LoadFile(fromLoc, filename))
        Thread sleep 100
        clients(2) ! DownloadFile(0, filename)
        Thread sleep 150
        println("testing now")
        assert(filesEqual(fromLoc, toLoc))
    }
}
