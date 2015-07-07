package ethanp.firstVersion

import java.io.File

import akka.actor._
import akka.event.LoggingReceive
import akka.pattern.ask
import ethanp.file.{FileInfo, FileToDownload, LocalP2PFile, Sha2}

import scala.collection.immutable.BitSet
import scala.collection.mutable
import scala.concurrent.duration._
import scala.language.postfixOps

/**
 * Ethan Petuchowski
 * 6/4/15
 */
class Client extends Actor with ActorLogging {

    /* FIELDS */

    val localFiles = mutable.Map.empty[String, LocalP2PFile]
    val localAbbrevs = mutable.Map.empty[Sha2, String]
    val knownTrackers = mutable.Set.empty[ActorRef] // Note: actor refs CAN be sent to remote machine
    implicit val timeout: akka.util.Timeout = 2 seconds
    val downloadDir = new File("downloads")
    if (!downloadDir.exists()) downloadDir.mkdir()
    var currentDownloads = Map.empty[FileInfo, ActorRef/*FileDownloaders*/]
    var notificationListeners = Set.empty[ActorRef]


    /* UTILITIES */

    def getDownloader(abbrev: Sha2): Option[ActorRef] = currentDownloads.find(_._1.abbreviation == abbrev).map(_._2)


    /* RECEIVE */

    override def receive: Receive = LoggingReceive {

        case LoadFile(pathString, name) =>
            notificationListeners += sender
            val localFile: LocalP2PFile = loadFile(pathString, name)
            log.info("sending to known trackers")
            knownTrackers.foreach(_ ! InformTrackerIHave(localFile.fileInfo))

        case TrackerLoc(ref) => knownTrackers += ref

        case m @ ListTracker(ref) => ref ! m

        case TrackerKnowledge(files) =>
            log.info(s"tracker ${sender().path} knows of the following files")
            files.zipWithIndex foreach { case (f, i) => println(s"${i+1}: ${f.fileInfo.filename}") }

        case TrackerSideError(errMsg) =>
            log.error(s"ERROR from tracker ${sender()}: $errMsg")

        case DownloadFileFrom(tracker, filename) =>
            notificationListeners += sender
            if (knownTrackers contains tracker) tracker ! DownloadFile(filename)
            else sender ! ClientError("I don't know that tracker")

        case m : FileToDownload =>
            // pass args to actor constructor (runtime IllegalArgumentException if you mess it up!)
            currentDownloads += m.fileInfo -> context.actorOf(
                Props(classOf[FileDownloader], m, downloadDir), name=s"file-${m.fileInfo.filename}")

        /* at this time, handling ChunkRequests is a *non-blocking* maneuver for a client */
        case ChunkRequest(infoAbbrev, chunkIdx) =>
            /* Note: Akka guarantees FIFO order (only!) between creating a child and sending it messages

              As it stands, the ChunkDownloader doesn't care who sends it Pieces.
                However, it DOES rely on the fact that the Piece sender is also the ChunkSuccess sender,
                  but that will still be the case with this change.
              */
            if (localAbbrevs contains infoAbbrev) {
                val p2PFile = localFiles(localAbbrevs(infoAbbrev))
                context.actorOf(Props(classOf[ChunkReplyer], p2PFile)) ! ReplyTo(sender(), chunkIdx)
            }
            else sender ! PeerSideError("file with that hash not known")

        case m @ SuccessfullyAdded(filename) => notificationListeners.foreach(_ ! m)
        case m @ DownloadSuccess(filename) => notificationListeners.foreach(_ ! m)

        case Ping(abbrev) =>
            import scala.concurrent.ExecutionContext.Implicits.global

            // Respond with if I am seeder or leecher; & if leecher which chunks I have.
            // This file (probably) shouldn't EXIST in localFiles until it's done,
            // so instead we must query the FileDownloader actor.
            if (localAbbrevs contains abbrev) sender ! Seeding
            else getDownloader(abbrev) match {
                case None => sender ! PeerSideError("I don't have that file")

                // I'm still downloading that file, so query DLer
                // for completion bitset and pass it to peer
                case Some(fileDLer) =>
                    val sen = sender() // must store ref for use in async closure?
                    val dlerBitSet = (fileDLer ? Ping(abbrev)).mapTo[BitSet]
                    dlerBitSet onSuccess { case b => sen ! Leeching(b) }
            }
    }


    /* RECEIVE METHODS */

    def loadFile(pathString: String, name: String): LocalP2PFile = {
        log.info(s"loading $pathString")
        val localFile = LocalP2PFile.loadFile(name, pathString)
        localFiles(name) = localFile
        localAbbrevs(localFile.fileInfo.abbreviation) = name
        localFile
    }
}


