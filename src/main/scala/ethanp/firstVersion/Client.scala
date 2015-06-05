package ethanp.firstVersion

import akka.actor.{ActorRef, Actor}
import ethanp.file.{LocalP2PFile, FileToDownload, P2PFile}
import ethanp.firstVersion.Master.NodeID

import scala.collection.mutable

/**
 * Ethan Petuchowski
 * 6/4/15
 */
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
