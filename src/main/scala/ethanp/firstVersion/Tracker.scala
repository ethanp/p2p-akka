package ethanp.firstVersion

import akka.actor.{ActorLogging, Actor}
import ethanp.file.FileToDownload
import ethanp.firstVersion.Master.NodeID

import scala.collection.mutable

/**
 * Ethan Petuchowski
 * 6/4/15
 */
class Tracker extends Actor with ActorLogging {
    var myId: NodeID = -1
    def prin(x: Any) = println(s"s$myId: $x")

    val knowledgeOf = mutable.Map.empty[String, FileToDownload]

    override def receive: Receive = {

        case id: Int =>
            myId = id
            println(s"tracker set its id to $myId")

        case m: ListTracker =>
            sender ! TrackerKnowledge(knowledgeOf.values.toList)

        case InformTrackerIHave(id, info) =>
            val desiredFilename = info.filename
            if (knowledgeOf contains desiredFilename) {
                if (knowledgeOf(desiredFilename).fileInfo != info) {
                    sender ! TrackerSideError(s"different file named $desiredFilename already tracked")
                }
                else {
                    val swarm = knowledgeOf(desiredFilename)
                    if (swarm.seeders.contains(id)) {
                        sender ! TrackerSideError("already knew you are seeding this file")
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
                knowledgeOf(desiredFilename) =
                    FileToDownload(
                        info,
                        seeders = Map(id → sender),
                        leechers = Map())
                sender ! SuccessfullyAdded(desiredFilename)
            }

        case DownloadFile(clientID, filename) =>
            if (knowledgeOf contains filename) {
                sender ! knowledgeOf(filename) // msg is of type [FileToDownload]
                knowledgeOf(filename).leechers += clientID → sender
                if (knowledgeOf(filename).seeders.contains(clientID)) {
                    knowledgeOf(filename).seeders -= clientID
                }
            }
            else TrackerSideError(s"I don't know a file called $filename")
    }
}
