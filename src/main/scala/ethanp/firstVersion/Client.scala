package ethanp.firstVersion

import java.io.File

import akka.actor._
import akka.event.LoggingReceive
import ethanp.file.{Sha2, FileToDownload, LocalP2PFile}
import ethanp.firstVersion.Master.NodeID

import scala.collection.mutable
import scala.util.{Failure, Success}

/**
 * Ethan Petuchowski
 * 6/4/15
 */
class Client extends Actor with ActorLogging {
    log.info("starting up")
    var myId: NodeID = -1

    val localFiles = mutable.Map.empty[String, LocalP2PFile]
    val localAbbrevs = mutable.Map.empty[Sha2, String]

    /* Note: actor refs CAN be sent to remote machine */
    val knownTrackers = mutable.Map.empty[NodeID, ActorRef]
    val trackerIDs = mutable.Map.empty[ActorRef, NodeID]

    val downloadDir = new File("downloads")
    if (!downloadDir.exists()) downloadDir.mkdir()

    var mostRecentTrackerListing: List[FileToDownload] = _
    var currentDownloads = List.empty[ActorRef] // FileDownloaders

    // for remembering whom to reply to
    var interestedParty: Option[ActorRef] = None

    override def receive: Receive = LoggingReceive {

        case id: Int =>
            myId = id
            println(s"client set its id to $myId")

        case LoadFile(pathString, name) =>
            interestedParty = Some(sender())
            log.info(s"loading $pathString")
            val localFile = LocalP2PFile.loadFile(name, pathString)
            localFiles(name) = localFile
            localAbbrevs(localFile.fileInfo.abbreviation) = name
            log.info("sending to known trackers")
            knownTrackers.values.foreach(_ ! InformTrackerIHave(myId, localFile.fileInfo))

        case TrackerLoc(id, ref) =>
            log.info(s"adding tracker $id")
            knownTrackers(id) = ref
            trackerIDs(ref) = id

        case m @ ListTracker(id) =>
            knownTrackers(id) ! m

        case TrackerKnowledge(files) =>
            mostRecentTrackerListing = files
            log.info(s"tracker ${trackerIDs(sender())} knows of the following files")
            files.zipWithIndex foreach { case (f, i) => println(s"${i+1}: ${f.fileInfo.filename}") }

        case TrackerSideError(errMsg) =>
            log.error(s"ERROR from ${trackerIDs(sender())}: $errMsg")

        case m @ DownloadFile(trackerID, filename) =>
            interestedParty = Some(sender())
            knownTrackers(trackerID) ! m

        case m : FileToDownload =>
            // pass args to actor constructor (runtime IllegalArgumentException if you mess it up!)
            currentDownloads ::= context.actorOf(
                Props(classOf[FileDownloader], m, downloadDir), name=s"file-${m.fileInfo.filename}")

        /* at this time, handling ChunkRequests is a *blocking* maneuver for a client */
        case ChunkRequest(infoAbbrev, chunkIdx) =>
            if (localAbbrevs contains infoAbbrev) {
                val p2PFile = localFiles(localAbbrevs(infoAbbrev))
                // no idear how best to handle failures here...
                try {
                    var pieceIdx = 0
                    val piecesThisChunk = p2PFile.fileInfo.numPiecesInChunk(chunkIdx)
                    var hasntFailed = true
                    def done: Boolean = pieceIdx == piecesThisChunk
                    while (!done && hasntFailed) {
                        p2PFile.getPiece(chunkIdx, pieceIdx) match {
                            case Success(arr) =>
                                // doesn't need to pass fileInfo bc it's being sent to
                                // particular ChunkDownloader
                                sender ! Piece(arr, pieceIdx)
                                pieceIdx += 1
                            case Failure(e) =>
                                log.error("request failed with "+e.getClass)
                                hasntFailed = false
                        }
                    }
                    if (done && hasntFailed) {
                        sender ! ChunkSuccess
                    }
                }
                catch {
                    case e: Throwable =>
                        log.error(e.toString)
                        log.error(s"couldn't read file ${p2PFile.fileInfo.filename}")
                        log.error("ignoring client request")
                        // don't exit or anything. just keep trucking along.
                }
            }
            else {
                sender ! PeerSideError("file with that hash not known")
            }

        case m @ SuccessfullyAdded(filename) => interestedParty.foreach(_ ! m)
        case m @ DownloadSuccess(filename) => interestedParty.foreach(_ ! m)
    }
}


