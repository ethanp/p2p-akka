package ethanp.firstVersion

import java.util.Scanner

import akka.actor._
import ethanp.file.{FileInfo, FileToDownload, LocalP2PFile, P2PFile}
import ethanp.firstVersion.Master.NodeID

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
    while (sc.hasNextLine) {
        val line = sc.nextLine().split(" ")
        lazy val slot1 = line(1)
        lazy val slot2 = line(2)
        lazy val slot3 = line(3)
        lazy val int1 = slot1.toInt
        lazy val int2 = slot2.toInt
        line(0) match {
            case "newTracker" ⇒
                val id = trackers.size
                trackers += sys.actorOf(Props[Tracker], "tracker-" + id)
                trackers(id) ! id
            case "newClient" ⇒
                val id = clients.size
                clients += sys.actorOf(Props[Client], "client-" + clients.size)
                clients(id) ! id
            case "addFile" ⇒ clients(int1) ! LoadFile(slot2, slot3)
            case "download" ⇒ ???
            case "giveClientTracker" ⇒ clients(int1) ! TrackerLoc(int2, trackers(int2))
            case "listTracker" ⇒ clients(int1) ! ListTracker(int2)
            case a ⇒ println(s"can't handle: $a")
        }
    }
}

class Tracker extends Actor {
    var myId: NodeID = -1
    def prin(x: Any) = println(s"s$myId: $x")

    val myKnowledge = mutable.Map.empty[String, FileToDownload]

    override def receive: Receive = {
        case id: Int ⇒
            myId = id
            println(s"tracker set its id to $myId")
        case m: ListTracker ⇒
            sender ! TrackerKnowledge(myKnowledge.values.toList)
        case InformTrackerIHave(id, info) ⇒
            val desiredFilename = info.filename
            if (myKnowledge contains desiredFilename) {
                if (myKnowledge(desiredFilename).fileInfo != info) {
                    sender ! TrackerSideError(s"different file named $desiredFilename already tracked")
                }
                else {
                    val swarm = myKnowledge(desiredFilename).swarm
                    if (swarm.seeders.contains(id)) {
                        TrackerSideError("already knew you are seeding this file")
                    }
                    else if (swarm.leechers.contains(id)) {
                        swarm.leechers -= id
                        swarm.seeders += id → sender
                        sender ! SuccessfullyAdded(desiredFilename)
                    }
                    else {
                        swarm.seeders += id → sender
                        sender ! SuccessfullyAdded(desiredFilename)
                    }
                }
            }
            else {
                myKnowledge(desiredFilename) =
                    FileToDownload(
                        info,
                        Swarm(
                            seeders = Map(id → sender),
                            leechers = Map()
                        )
                    )
                sender ! SuccessfullyAdded(desiredFilename)
            }
    }
}

class Client extends Actor {
    var myId: NodeID = -1
    def prin(x: Any) = println(s"c$myId: $x")

    val localFiles = mutable.Map.empty[String, P2PFile]

    /* Note: actor refs CAN be sent to remote machine */
    val knownTrackers = mutable.Map.empty[NodeID, ActorRef]
    val trackerIDs = mutable.Map.empty[ActorRef, NodeID]

    var mostRecentTrackerListing: List[FileToDownload] = _

    override def receive: Receive = {
        case id: Int ⇒
            myId = id
            println(s"client set its id to $myId")
        case LoadFile(pathString, name) ⇒
            prin(s"loading $pathString")
            val localFile = LocalP2PFile.loadFile(name, pathString)
            localFiles(name) = localFile
            prin("sending to known trackers")
            knownTrackers.values.foreach(_ ! InformTrackerIHave(myId, localFile.fileInfo))
        case TrackerLoc(id, ref) ⇒
            prin(s"adding tracker $id")
            knownTrackers(id) = ref
            trackerIDs(ref) = id
        case m @ ListTracker(id) ⇒
            knownTrackers(id) ! m
        case TrackerKnowledge(files) ⇒
            mostRecentTrackerListing = files
            prin(s"tracker ${trackerIDs(sender())} knows of the following files")
            files.zipWithIndex foreach { case (f, i) ⇒ println(s"${i+1}: ${f.fileInfo.filename}") }
        case TrackerSideError(errMsg) ⇒
            prin(s"ERROR from ${trackerIDs(sender())}: $errMsg")
    }
}

case class LoadFile(pathString: String, name: String)
case class TrackerLoc(trackerID: NodeID, trackerPath: ActorRef)
case class ListTracker(trackerID: NodeID)
case class Swarm(var seeders: Map[NodeID, ActorRef], var leechers: Map[NodeID, ActorRef])
case class TrackerKnowledge(knowledge: List[FileToDownload])
case class InformTrackerIHave(clientID: NodeID, fileInfo: FileInfo)
case class TrackerSideError(filename: String)
case class SuccessfullyAdded(filename: String)
