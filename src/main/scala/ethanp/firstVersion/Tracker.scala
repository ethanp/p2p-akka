package ethanp.firstVersion

import akka.actor.{ActorRef, ActorLogging, Actor}
import ethanp.file.FileToDownload
import ethanp.firstVersion.Master.NodeID

import scala.collection.mutable

/**
 * Ethan Petuchowski
 * Created 6/4/15
 *
 * From the Tracker's perspective, it doesn't matter how clients
 * "interested in the file" are split between seeders and leechers;
 * but in the eyes of a prospective downloader, it may matter.
 */
class Tracker extends Actor with ActorLogging {

    // TODO remove the [needless] reliance on myId
    /**
     * I don't know why I made the ID so important
     * bc one can't possibly keep them unique across network partitions...
     */
    var myId: NodeID = -1
    def prin(x: Any) = println(s"s$myId: $x")

    val knowledgeOf = mutable.Map.empty[String, FileToDownload]

    // I would like to know a better way to do this while keeping things immutable
    def addSeeder(filename: String, id: NodeID, sndr: ActorRef) {
        knowledgeOf(filename) = knowledgeOf(filename).addSeeder(id, sndr)
    }
    def addLeecher(filename: String, id: NodeID, sndr: ActorRef) {
        knowledgeOf(filename) = knowledgeOf(filename).addLeecher(id, sndr)
    }
    def subtractSeeder(filename: String, id: NodeID) {
        knowledgeOf(filename) = knowledgeOf(filename).subtractSeeder(id)
    }
    def subtractLeecher(filename: String, id: NodeID) {
        knowledgeOf(filename) = knowledgeOf(filename).subtractLeecher(id)
    }


    override def receive: Receive = {

        case id: Int => myId = id

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
                        subtractSeeder(desiredFilename, id)
                        addLeecher(desiredFilename, id, sender())
                        sender ! SuccessfullyAdded(desiredFilename)
                    }
                    else {
                        addSeeder(desiredFilename, id, sender())
                        sender ! SuccessfullyAdded(desiredFilename)
                    }
                }
            }
            else {
                knowledgeOf(desiredFilename) = FileToDownload(info, Map(id → sender), Map())
                sender ! SuccessfullyAdded(desiredFilename)
            }

        case DownloadFile(clientID, filename) =>
            if (knowledgeOf contains filename) {
                sender ! knowledgeOf(filename) // msg is of type [FileToDownload]
                addLeecher(filename, clientID, sender())
                if (knowledgeOf(filename).seeders.contains(clientID)) {
                    subtractSeeder(filename, clientID)
                }
            }
            else sender ! TrackerSideError(s"I don't know a file called $filename")
    }
}
