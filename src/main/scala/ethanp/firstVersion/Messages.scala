package ethanp.firstVersion

import akka.actor.ActorRef
import ethanp.file.{FileInfo, FileToDownload}
import ethanp.firstVersion.Master.NodeID

/**
 * Ethan Petuchowski
 * 6/4/15
 */
case class LoadFile(pathString: String, name: String)
case class TrackerLoc(trackerID: NodeID, trackerPath: ActorRef)
case class ListTracker(trackerID: NodeID)
case class Swarm(var seeders: Map[NodeID, ActorRef], var leechers: Map[NodeID, ActorRef])
case class TrackerKnowledge(knowledge: List[FileToDownload])
case class InformTrackerIHave(clientID: NodeID, fileInfo: FileInfo)
case class TrackerSideError(filename: String)
case class SuccessfullyAdded(filename: String)
