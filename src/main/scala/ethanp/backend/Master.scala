package ethanp.backend

import java.util.Scanner

import akka.actor.{ActorRef, ActorSystem, Props}
import ethanp.backend.client._

import scala.collection.mutable

/**
  * Ethan Petuchowski
  * 6/3/15
  */
object Master extends App {
    type NodeID = Int
    // side note: `type` in a `class` makes a "type member" NOT an "alias"
    val sc = new Scanner(System.in)
    val trackers = mutable.ArrayBuffer.empty[ActorRef]
    val clients = mutable.ArrayBuffer.empty[ActorRef]
    val sys = ActorSystem("as_if")
    println("why hello there!")
    while (sc.hasNextLine) {
        val line = sc.nextLine().split(" ")
        lazy val slot1 = line(1)
        lazy val slot2 = line(2)
        lazy val slot3 = line(3)
        lazy val int1 = slot1.toInt
        lazy val int2 = slot2.toInt
        line(0) match {
            case "newTracker" => trackers += sys.actorOf(Props[Tracker], "tracker-" + trackers.size)
            case "newClient" => clients += sys.actorOf(Props[Client], "client-" + clients.size)
            case "addFile" => clients(int1) ! LoadFile(slot2, slot3)
            case "download" => clients(int1) ! DownloadFileFrom(trackerLoc = trackers(int2), filename = slot3)
            case "giveClientTracker" => clients(int1) ! TrackerLoc(trackers(int2))
            case "listTracker" => clients(int1) ! ListTracker(trackers(int2))
            case a => println(s"can't handle: $a")
        }
    }
}
