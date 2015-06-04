package ethanp.firstVersion

import java.util.Scanner

import akka.actor.{Props, Actor, ActorSystem, ActorRef}

import scala.collection.mutable

/**
 * Ethan Petuchowski
 * 6/3/15
 */
object Master extends App {
    val sc = new Scanner(System.in)
    val trackers = mutable.MutableList.empty[ActorRef]
    val clients = mutable.MutableList.empty[ActorRef]
    val sys = ActorSystem("alltheworldsarave")
    while (sc.hasNextLine) {
        val line = sc.nextLine().split(" ")
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
            case "download"   ⇒
        }
    }
}

class Tracker extends Actor {
    var myId: Int = -1
    override def receive: Receive = {
        case id: Int ⇒
            myId = id
            println(s"tracker set its id to $myId")
    }
}

class Client extends Actor {
    override def receive: Actor.Receive = ???
}
