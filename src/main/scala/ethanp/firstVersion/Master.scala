package ethanp.firstVersion

import java.util.Scanner

import akka.actor.{Props, ActorSystem, ActorRef}

import scala.collection.mutable

/**
 * Ethan Petuchowski
 * 6/3/15
 */
object Master extends App {
    type NodeID = Int
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
            case "newTracker" =>
                val id = trackers.size
                trackers += sys.actorOf(Props[Tracker], "tracker-" + id)
                trackers(id) ! id
            case "newClient" =>
                val id = clients.size
                clients += sys.actorOf(Props[Client], "client-" + clients.size)
                clients(id) ! id
            case "addFile" => clients(int1) ! LoadFile(slot2, slot3)
            case "download" => clients(int1) ! DownloadFile(nodeID=int2, filename=slot3)
            case "giveClientTracker" => clients(int1) ! TrackerLoc(int2, trackers(int2))
            case "listTracker" => clients(int1) ! ListTracker(int2)
            case a => println(s"can't handle: $a")
        }
    }
}
