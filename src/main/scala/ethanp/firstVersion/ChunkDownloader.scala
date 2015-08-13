package ethanp.firstVersion

import java.io.RandomAccessFile

import akka.actor._
import akka.event.LoggingReceive
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

    /* an IOException here will crash the program. I don't really have any better ideas...retry?
     * I'll address it if it comes up
     */
    def writeChunk(): Boolean = {
        if (Sha2.hashOf(chunkData) == p2PFile.fileInfo.chunkHashes(chunkIdx)) {
            log.warning(s"writing out all ${chunkData.length} bytes of chunk $chunkIdx")
            /* use rwd or rws to write synchronously. until I have (hard to debug) issues, I'm going
             * with writing asynchronously */
            // this is probably my own file descriptor, so other threads can't seek me somewhere else
            // before I write out the data
            val out = new RandomAccessFile(p2PFile.file, "rw")
            // Setting offset beyond end of file does not change file length.
            // File length changes by writing after offset beyond end of file.
            out.seek(chunkIdx * BYTES_PER_CHUNK)
            out.write(chunkData)
            out.close()
            true
        }
        else {
            log error s"$this rcvd chunk that didn't hash correctly"
            false
        }
    }

    override def preStart(): Unit = {
        /* The docs say:
         *      Once set, the receive timeout stays in effect (i.e. continues firing repeatedly
         *      after inactivity periods). Pass in `Duration.Undefined` to switch off this feature.
         * In another word, I guess I don't need to set another timeout after every message.
         */
        context.setReceiveTimeout(receiveTimeout)
        peerRef ! ChunkRequest(p2PFile.fileInfo.abbreviation, chunkIdx)
    }

    def notifyListenersAndDie(msg: ChunkStatus) {
        listeners foreach (_ ! msg)
        self ! PoisonPill
    }
    def chunkXferSuccess() = notifyListenersAndDie(ChunkComplete(chunkIdx))
    def chunkXferFailed() = notifyListenersAndDie(ChunkDLFailed(chunkIdx, peerRef))

    override def receive: Actor.Receive = LoggingReceive {
        case Piece(data, idx) =>
            context.parent ! DownloadSpeed(data.length)

            piecesRcvd(idx) = true
            for ((b, i) ← data.zipWithIndex) {
                chunkData(idx*BYTES_PER_PIECE + i) = b
            }

        // enabled via context.setReceiveTimeout above
        case ReceiveTimeout => chunkXferFailed()

        case ChunkSuccess =>
            val success = writeChunk()
            if (success) chunkXferSuccess()
            else chunkXferFailed()

        case AddMeAsListener =>
            listeners += sender
    }
}
