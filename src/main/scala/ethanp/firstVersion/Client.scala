package ethanp.firstVersion

import java.io.File

import akka.actor._
import akka.event.LoggingReceive
import akka.pattern.ask
import ethanp.file.{FileInfo, FileToDownload, LocalP2PFile, Sha2}

import scala.collection.immutable.BitSet
import scala.collection.mutable
import scala.collection.immutable
import scala.concurrent.Future
import scala.util.{Failure, Success}

/**
 * Ethan Petuchowski
 * 6/4/15
 */
class Client extends Actor with ActorLogging {
    log.info(s"client $self starting up")

    val localFiles = mutable.Map.empty[String, LocalP2PFile]
    val localAbbrevs = mutable.Map.empty[Sha2, String]

    /* Note: actor refs CAN be sent to remote machine */
    val knownTrackers = mutable.Set.empty[ActorRef]

    val downloadDir = new File("downloads")
    if (!downloadDir.exists()) downloadDir.mkdir()

    var mostRecentTrackerListing: List[FileToDownload] = _
    var currentDownloads = Map.empty[FileInfo, ActorRef] // FileDownloaders

    def getDownloader(abbrev: Sha2): Option[ActorRef] = currentDownloads.find(_._1.abbreviation == abbrev).map(_._2)

    // for remembering whom to reply to
    var testerActor: Option[ActorRef] = None

    override def receive: Receive = LoggingReceive {

        case LoadFile(pathString, name) =>
            testerActor = Some(sender())
            log.info(s"loading $pathString")
            val localFile = LocalP2PFile.loadFile(name, pathString)
            localFiles(name) = localFile
            localAbbrevs(localFile.fileInfo.abbreviation) = name
            log.info("sending to known trackers")
            knownTrackers.foreach(_ ! InformTrackerIHave(localFile.fileInfo))

        case TrackerLoc(ref) => knownTrackers += ref

        case m @ ListTracker(ref) => ref ! m

        case TrackerKnowledge(files) =>
            mostRecentTrackerListing = files
            log.info(s"tracker ${sender().path} knows of the following files")
            files.zipWithIndex foreach { case (f, i) => println(s"${i+1}: ${f.fileInfo.filename}") }

        case TrackerSideError(errMsg) =>
            log.error(s"ERROR from tracker ${sender()}: $errMsg")

        case m @ DownloadFileFrom(tracker, filename) =>
            testerActor = Some(sender())
            if (!(knownTrackers contains tracker)) {
                sender ! ClientError("I don't know that tracker")
            }
            else {
                tracker ! DownloadFile(filename)
            }

        case m : FileToDownload =>
            // pass args to actor constructor (runtime IllegalArgumentException if you mess it up!)
            currentDownloads += m.fileInfo -> context.actorOf(
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
                        // don't exit or anything. just keep trucking.
                }
            }
            else {
                sender ! PeerSideError("file with that hash not known")
            }

        case m @ SuccessfullyAdded(filename) => testerActor.foreach(_ ! m)
        case m @ DownloadSuccess(filename) => testerActor.foreach(_ ! m)

        case Ping(abbrev) =>
            // Respond with if I am seeder or leecher; & if leecher which chunks I have.
            // This file (probably) shouldn't EXIST in localFiles until it's done,
            // so instead we must query the FileDownloader actor.
            if (localAbbrevs contains abbrev) sender ! Seeding
            else getDownloader(abbrev) match {
                case None => sender ! PeerSideError("I don't have that file")
                case Some(fileDLer) =>
                    val sen = sender() // must store ref for use in async closure!
                    (fileDLer ? Ping(abbrev)).mapTo[BitSet].onSuccess { case b => sen ! Leeching(b) }
            }
    }
}


