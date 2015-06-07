package ethanp.firstVersion

import java.io.{File, RandomAccessFile}

import akka.actor._
import akka.event.LoggingReceive
import ethanp.file.LocalP2PFile._
import ethanp.file.{FileToDownload, LocalP2PFile}
import ethanp.firstVersion.Master.NodeID

import scala.collection.mutable
import scala.concurrent.duration._
import scala.util.{Failure, Success}

/**
 * Ethan Petuchowski
 * 6/4/15
 */
class Client extends Actor with ActorLogging {
    log.info("starting up")
    var myId: NodeID = -1
    def prin(x: Any) = println(s"c$myId: $x")
    def prinErr(x: Any) = System.err.println(s"c$myId: $x")

    val localFiles = mutable.Map.empty[String, LocalP2PFile]

    /* Note: actor refs CAN be sent to remote machine */
    val knownTrackers = mutable.Map.empty[NodeID, ActorRef]
    val trackerIDs = mutable.Map.empty[ActorRef, NodeID]

    val downloadDir = new File("downloads")
    if (!downloadDir.exists()) downloadDir.mkdir()

    var mostRecentTrackerListing: List[FileToDownload] = _
    var currentDownloads = List.empty[ActorRef] // FileDownloaders


    override def receive: Receive = LoggingReceive {

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

        case m @ DownloadFile(trackerID, filename) ⇒
            knownTrackers(trackerID) ! m

        case m : FileToDownload ⇒
            // pass args to actor constructor (runtime IllegalArgumentException if you mess it up!)
            currentDownloads ::= context.actorOf(
                Props(classOf[FileDownloader], m, downloadDir), name=s"file-${m.fileInfo.filename}")

        /* at this time, handling ChunkRequests is a *blocking* maneuver for a client */
        case ChunkRequest(fileInfo, chunkIdx) ⇒
            if (localFiles contains fileInfo.filename) {
                val p2PFile = localFiles(fileInfo.filename)
                if (p2PFile.fileInfo != fileInfo) {
                    sender ! PeerSideError("file by that name has different hashes")
                } else {
                    // no idear how best to handle failures here...
                    try {
                        var pieceIdx = 0
                        val piecesThisChunk = p2PFile.fileInfo.numPiecesInChunk(chunkIdx)
                        var hasntFailed = true
                        def done: Boolean = pieceIdx == piecesThisChunk
                        while (!done && hasntFailed) {
                            p2PFile.getPiece(chunkIdx, pieceIdx) match {
                                case Success(arr) ⇒
                                    sender ! Piece(arr, pieceIdx)
                                    pieceIdx += 1
                                case Failure(e) ⇒
                                    prinErr("request failed with "+e.getClass)
                                    hasntFailed = false
                            }
                        }
                        if (done && hasntFailed) {
                            sender ! ChunkSuccess
                        }
                    }
                    catch {
                        case e: Throwable ⇒
                            prinErr(e)
                            prinErr(s"couldn't read file ${fileInfo.filename}")
                            prinErr("ignoring client request")
                    }
                }
            }
            else {
                sender ! PeerSideError("file by that name not known")
            }

        case SuccessfullyAdded(filename) ⇒ // TODO do something?
    }
}

/** sure, this is simplistic, but making it better is future work. */
class FileDownloader(fileDLing: FileToDownload, downloadDir: File) extends Actor with ActorLogging {
    import context._
    val filename = fileDLing.fileInfo.filename

    val localFile = new File(downloadDir, filename)
    if (localFile.exists()) {
        // TODO FIXME!
        localFile.delete()
    }
    val p2PFile = LocalP2PFile(fileDLing.fileInfo, localFile)

    /** peers we've timed-out upon recently */
    var quarantine = List.empty[PeerLoc]

    var seederMap: Map[NodeID, ActorRef] = fileDLing.swarm.seeders
    var chunkDownloads = List.empty[ActorRef] // ChunkDownloaders

    var seederNum = 0
    def nextSeeder = {
        seederNum += 1
        seederMap.toList(seederNum % seederMap.size)
    }

    var chunksComplete = new Array[Boolean](fileDLing.fileInfo.numChunks)
    var chunksInProgress = new Array[Boolean](fileDLing.fileInfo.numChunks)

    def chooseNextChunk(): (Int, PeerLoc) = {
        // collectFirst: Finds the first element of the traversable or iterator for which
        // the given partial function is defined, and applies the partial function to it.
        chunksInProgress.zipWithIndex.collectFirst {
            case (inProgress, idx) if !inProgress ⇒
                chunksInProgress(idx) = true
                idx → PeerLoc(nextSeeder)
        }.get
    }

    def addChunkDownload(): Unit = {
        if ((chunksInProgress filterNot identity).nonEmpty) {
            val (nextChunkIdx, peerLoc) = chooseNextChunk()
            downloadChunkFrom(nextChunkIdx, peerLoc)
        } else if ((chunksComplete filterNot identity).isEmpty) {
            speedometer.cancel()
            log.debug(s"transfer of $filename complete!")
            self ! PoisonPill
        }
    }

    def downloadChunkFrom(chunkIdx: Int, peerLoc: PeerLoc): Unit = {
        chunkDownloads ::= context.actorOf(
            Props(classOf[ChunkDownloader], p2PFile, chunkIdx, peerLoc), name = s"chunk-$chunkIdx")
    }

    /** called by Akka framework when this Actor is asynchronously started */
    override def preStart(): Unit = addChunkDownload()

    /* speed calculations */
    var bytesDLedPastSecond = 0

    val speedometer = context.system.scheduler.schedule(1.second, 1.second) {
        log.info(f"current DL speed for $filename: ${bytesDLedPastSecond.toDouble / 1000}%.2f")
        bytesDLedPastSecond = 0
    }

    override def receive: Actor.Receive = LoggingReceive {
        case ChunkComplete(idx) ⇒
            chunksComplete(idx) = true
            addChunkDownload()

        // this is received *after* the ChunkDownloader tried retrying a few times
        case ChunkDLFailed(idx, peerLoc) ⇒
            seederMap -= peerLoc.peerID
            if (seederMap.nonEmpty) downloadChunkFrom(idx, PeerLoc(nextSeeder))
            else log.warning(s"$filename seederList is empty")

        case DownloadSpeed(numBytes) ⇒ bytesDLedPastSecond += numBytes // should be child-actor
    }
}

class ChunkDownloader(p2PFile: LocalP2PFile, chunkIdx: Int, peer: PeerLoc) extends Actor with ActorLogging {

    val piecesRcvd = new Array[Boolean](p2PFile.fileInfo numPiecesInChunk chunkIdx)
    val chunkData = new Array[Byte](p2PFile.fileInfo numBytesInChunk chunkIdx)

    var numRetries = 0 // TODO not implemented

    /* an IOException here will crash the program. I don't really have any better ideas...retry? */
    def writeChunk() {
        log.info(s"writing out all ${chunkData.length} bytes of chunk $chunkIdx")
        /* use rwd or rws to write synchronously. until I have (hard to debug) issues, I'm going
         * with writing asynchronously */
        val out = new RandomAccessFile(p2PFile.file, "rw")
        // Setting offset beyond end of file does not change file length.
        // File length changes by writing after offset beyond end of file.
        out.seek(chunkIdx*BYTES_PER_CHUNK)
        out.write(chunkData)
        out.close()
    }

    override def preStart(): Unit = {
        /* The docs say:
         *      Once set, the receive timeout stays in effect (i.e. continues firing repeatedly
         *      after inactivity periods). Pass in `Duration.Undefined` to switch off this feature.
         * In another word, I guess I don't need to set another timeout after every message.
         */
        context.setReceiveTimeout(1.second)
        peer.peerPath ! ChunkRequest(p2PFile.fileInfo, chunkIdx)
    }

    override def receive: Actor.Receive = LoggingReceive {
        case Piece(data, idx) ⇒
            context.parent ! DownloadSpeed(data.length)
            log.info(s"received piece $idx")
            piecesRcvd(idx) = true
            log.info("total pieces received: "+piecesRcvd.filter(identity).length)
            for ((b, i) ← data.zipWithIndex) {
                val byteIdx = idx * BYTES_PER_PIECE + i
                chunkData(byteIdx) = b
            }
        case ReceiveTimeout ⇒ context.parent ! ChunkDLFailed(chunkIdx, peer)
        case ChunkSuccess ⇒
            writeChunk() // blocking call
            context.parent ! ChunkComplete(chunkIdx)
            self ! PoisonPill
    }
}
