package ethanp.backend

import java.io.RandomAccessFile

import akka.actor._
import akka.event.LoggingReceive
import ethanp.backend.client._
import ethanp.file.LocalP2PFile._
import ethanp.file.{LocalP2PFile, Sha2}

import scala.concurrent.duration._

/**
 * Ethan Petuchowski
 * 6/6/15
 */
class ChunkDownloader(p2PFile: LocalP2PFile, chunkIdx: Int, peerRef: ActorRef) extends Actor with ActorLogging {

    /* CONFIGURATION */

    var receiveTimeout = 2.seconds

    /* FIELDS */

    val piecesRcvd = new Array[Boolean](p2PFile.fileInfo numPiecesInChunk chunkIdx)
    val chunkData = new Array[Byte](p2PFile.fileInfo numBytesInChunk chunkIdx)
    var listeners = Set(context.parent)

    /**
     * Write the downloaded Chunk to the local filesystem iff it hashes correctly.
     *
     * Note: An IOException here will crash the program. I don't really have any better ideas...retry?
     * I'll address it if it comes up
     */
    def writeChunk(): Boolean = {

        // precondition: data shouldn't already be there
        if (p2PFile hasDataForChunk chunkIdx)
            throw new IllegalStateException()

        if ((Sha2 hashOf chunkData) == p2PFile.fileInfo.chunkHashes(chunkIdx)) {
            log.debug(s"writing out all ${chunkData.length} bytes of chunk $chunkIdx")

            /*
             * Use rwd or rws to write synchronously.
             * Until I have (hard to debug) issues, I'm going with writing asynchronously.
             *
             * I'm assuming this creates its own file descriptor, so other threads can't
             * this guy somewhere else before I write out the data here
             */
            val out = new RandomAccessFile(p2PFile.file, "rw")

            // Setting offset beyond end-of-file does not change the file length.
            // Writing beyond end-of-file does change the file length.
            out.seek(chunkIdx * BYTES_PER_CHUNK)
            out.write(chunkData)
            out.close()

            p2PFile.unavailableChunkIndexes.remove(chunkIdx)
            true
        }
        else {
            log error s"$this rcvd chunk data that didn't hash correctly"
            false
        }
    }

    /**
     * Upon booting up, the ChunkDownloader will request the Chunk from the Peer
     * and set a Timeout for the Peer's response.
     */
    override def preStart(): Unit = {

        /* The docs say:
         *
         *      Once set, the receive timeout stays in effect (i.e. continues firing repeatedly
         *      after inactivity periods). Pass in `Duration.Undefined` to switch off this feature.
         *
         * This means we don't need to set another timeout after every message.
         */
        context.setReceiveTimeout(receiveTimeout)

        peerRef ! ChunkRequest(p2PFile.fileInfo.abbreviation, chunkIdx)
    }

    def notifyListenersAndDie(msg: ChunkStatus) {
        listeners foreach (_ ! msg)
        self ! PoisonPill
    }
    def chunkXferSuccess() = notifyListenersAndDie(ChunkComplete(chunkIdx))
    def chunkXferFailed(cause: FailureMechanism) = notifyListenersAndDie(ChunkDLFailed(chunkIdx, peerRef, cause))

    override def receive: Actor.Receive = LoggingReceive {
        case Piece(data, idx) =>
            context.parent ! DownloadSpeed(data.length)

            piecesRcvd(idx) = true
            for ((b, i) ← data.zipWithIndex) {
                chunkData(idx*BYTES_PER_PIECE + i) = b
            }

        // enabled via context.setReceiveTimeout above
        case ReceiveTimeout => chunkXferFailed(TransferTimeout)

        case ChunkSuccess =>
            val success = writeChunk()
            if (success) chunkXferSuccess()
            else chunkXferFailed(InvalidData)

        case AddMeAsListener =>
            listeners += sender
    }
}
