package ethanp.firstVersion

import java.util.Scanner

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import ethanp.file.{LocalP2PFile, P2PFile}

import scala.collection.mutable

/**
 * Ethan Petuchowski
 * 6/3/15
 */
object Master extends App {
    val sc = new Scanner(System.in)
    val trackers = mutable.MutableList.empty[ActorRef]
    val clients = mutable.MutableList.empty[ActorRef]
    val sys = ActorSystem("as_if")
    while (sc.hasNextLine) {
        val line = sc.nextLine().split(" ")
        lazy val slot1 = line(1)
        lazy val slot2 = line(2)
        lazy val slot3 = line(3)
        lazy val int1 = slot1.toInt
        line(0) match {
            case "newTracker" ⇒
                val id = trackers.size
                trackers += sys.actorOf(Props[Tracker], "tracker-"+id)
                trackers(id) ! id
            case "newClient"  ⇒
                val id = clients.size
                clients += sys.actorOf(Props[Client], "client-"+clients.size)
                clients(id) ! id
            case "addFile"    ⇒
                clients(int1) ! LoadFile(slot2, slot3)
            case "download"   ⇒
            case a ⇒ println(s"can't handle: $a")
        }
    }
}

class Tracker extends Actor {
    var myId: Int = -1
    def prin(x: Any) = println(s"s$myId: $x")
    override def receive: Receive = {
        case id: Int ⇒
            myId = id
            println(s"tracker set its id to $myId")
    }
}

class Client extends Actor {
    var myId: Int = -1
    def prin(x: Any) = println(s"c$myId: $x")
    val localFiles = mutable.Map.empty[String, P2PFile]

    override def receive: Receive = {
        case id: Int ⇒
            myId = id
            println(s"client set its id to $myId")
        case LoadFile(pathString, name) ⇒
            prin(s"loading $pathString")
            localFiles(name) = LocalP2PFile.loadFrom(pathString)
            println("chunk hashes:\n----------")
            localFiles(name).chunkHashes.foreach(println)
            println(s"file hash: ${localFiles(name).fileHash}")
    }
}

case class LoadFile(pathString: String, name: String)
