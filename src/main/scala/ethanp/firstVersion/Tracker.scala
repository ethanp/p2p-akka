package ethanp.firstVersion

import akka.actor.Actor
import ethanp.file.FileToDownload
import ethanp.firstVersion.Master.NodeID

import scala.collection.mutable

/**
 * Ethan Petuchowski
 * 6/4/15
 */
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