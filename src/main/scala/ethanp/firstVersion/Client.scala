package ethanp.firstVersion

import akka.actor.{Actor, ActorRef, Props, ReceiveTimeout}
import ethanp.file.{FileInfo, FileToDownload, LocalP2PFile}
import ethanp.firstVersion.Master.NodeID
import ethanp.file.LocalP2PFile._

import scala.collection.mutable
import scala.concurrent.duration._
import scala.util.{Failure, Success}

// implicits like "seconds", etc.

/**
 * Ethan Petuchowski
 * 6/4/15
 */
class Client extends Actor {
    var myId: NodeID = -1
    def prin(x: Any) = println(s"c$myId: $x")
    def prinErr(x: Any) = System.err.println(s"c$myId: $x")

    val localFiles = mutable.Map.empty[String, LocalP2PFile]

    /* Note: actor refs CAN be sent to remote machine */
    val knownTrackers = mutable.Map.empty[NodeID, ActorRef]
    val trackerIDs = mutable.Map.empty[ActorRef, NodeID]

    var mostRecentTrackerListing: List[FileToDownload] = _
    var currentDownloads = List.empty[ActorRef] // FileDownloaders


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

        case m @ DownloadFile(trackerID, filename) ⇒
            knownTrackers(trackerID) ! m

        case m : FileToDownload ⇒
            // pass args to actor constructor (runtime IllegalArgumentException if you mess it up!)
            currentDownloads ::= context.actorOf(Props(classOf[FileDownloader], m))

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
                        def done = pieceIdx == piecesThisChunk
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
                        case e: Exception ⇒
                            prinErr(s"couldn't read file ${fileInfo.filename}")
                            prinErr("ignoring client request")
                    }
                }
            }
            else {
                sender ! PeerSideError("file by that name not known")
            }
    }
}

/** sure, this is simplistic, but making it better is future work. */
class FileDownloader(fileToDownload: FileToDownload) extends Actor {

    /** peers we've timed-out upon recently */
    var quarantine = List.empty[PeerLoc]

    var seederList: Map[NodeID, ActorRef] = fileToDownload.swarm.seeders
    var chunkDownloads = List.empty[ActorRef] // ChunkDownloaders

    var chunksComplete = ???

    def chooseNextChunk(): (Int, PeerLoc) = ???

    def addChunkDownload(): Unit = {
        val (nextChunkIdx, peerLoc) = chooseNextChunk()
        chunkDownloads ::= context.actorOf(
            Props(classOf[ChunkDownloader], fileToDownload.fileInfo, nextChunkIdx, peerLoc))
    }

    /** called by Akka framework when this Actor is asynchronously started */
    override def preStart(): Unit = {
        // TODO create a bunch of chunk-downloader children which download one piece at a time
        addChunkDownload()
    }

    override def receive: Actor.Receive = {
        // TODO receive callbacks from the chunk-downloaders,
        //      and fire up new ones based on the speed of download?
        // then once it's all done, become(downloadFinalizerOfSomeSort)
        case ChunkComplete(idx) ⇒ ???
        case TimedOutOn(peerLoc) ⇒ ???
    }
}

class ChunkDownloader(fileInfo: FileInfo, chunkIdx: Int, peer: PeerLoc) extends Actor {

    val piecesRcvd = Array.fill[Boolean](fileInfo numPiecesInChunk chunkIdx)(false)
    val chunkData = new Array[Byte](fileInfo numBytesInChunk chunkIdx)

    /* for calculating how long it took */
    var timeRequestSent = ???

    def chooseNextPiece(): Int = ???

    override def preStart(): Unit = {
        // set a timer-outer so that if no Piece is received in x-seconds
        // this actor sends the fileDownloader a PeerTimeout,
        // and the FileDownloader responds with a new PeerLoc
        context.setReceiveTimeout(3.seconds)
        peer.peerPath ! ChunkRequest(fileInfo, chunkIdx)
    }

    override def receive: Actor.Receive = {
        case Piece(data, idx) ⇒
            context.setReceiveTimeout(3.seconds)
            piecesRcvd(idx) = true
            println("pieces received: "+piecesRcvd.filter(identity))
            for ((b, i) ← data.zipWithIndex)
                chunkData(idx*BYTES_PER_PIECE+i) = b
        case ReceiveTimeout ⇒ context.parent ! TimedOutOn(peer)
        case ChunkSuccess ⇒ context.parent ! ChunkComplete(chunkIdx)
    }
}
