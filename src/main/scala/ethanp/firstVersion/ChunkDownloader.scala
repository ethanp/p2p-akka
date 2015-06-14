package ethanp.firstVersion

import java.io.RandomAccessFile

import akka.actor._
import akka.event.LoggingReceive
import ethanp.file.{Sha2, LocalP2PFile}
import ethanp.file.LocalP2PFile._

import scala.concurrent.duration._

/**
 * Ethan Petuchowski
 * 6/6/15
 */
class ChunkDownloader(p2PFile: LocalP2PFile, chunkIdx: Int, peerRef: ActorRef) extends Actor with ActorLogging {

    val piecesRcvd = new Array[Boolean](p2PFile.fileInfo numPiecesInChunk chunkIdx)
    val chunkData = new Array[Byte](p2PFile.fileInfo numBytesInChunk chunkIdx)

    /* an IOException here will crash the program. I don't really have any better ideas...retry? */
    def writeChunk() = {
        if (Sha2.hashOf(chunkData) == p2PFile.fileInfo.chunkHashes(chunkIdx)) {
            log.warning(s"writing out all ${chunkData.length} bytes of chunk $chunkIdx")
            /* use rwd or rws to write synchronously. until I have (hard to debug) issues, I'm going
         * with writing asynchronously */
            val out = new RandomAccessFile(p2PFile.file, "rw")
            // Setting offset beyond end of file does not change file length.
            // File length changes by writing after offset beyond end of file.
            out.seek(chunkIdx * BYTES_PER_CHUNK)
            out.write(chunkData)
            out.close()
            chunkXferSuccess
        }
        else {
            log.error(s"rcvd chunk for $this didn't hash correctly")
            chunkXferFailed
        }
    }

    override def preStart(): Unit = {
        /* The docs say:
         *      Once set, the receive timeout stays in effect (i.e. continues firing repeatedly
         *      after inactivity periods). Pass in `Duration.Undefined` to switch off this feature.
         * In another word, I guess I don't need to set another timeout after every message.
         */
        context.setReceiveTimeout(15.second)
        peerRef ! ChunkRequest(p2PFile.fileInfo.abbreviation, chunkIdx)
    }

    def notifyParent(msg: ChunkStatus) {
        context.parent ! msg
        self ! PoisonPill
    }
    def chunkXferSuccess = notifyParent(ChunkComplete(chunkIdx))
    def chunkXferFailed = notifyParent(ChunkDLFailed(chunkIdx, peerRef))

    override def receive: Actor.Receive = LoggingReceive {
        case Piece(data, idx) =>
            context.parent ! DownloadSpeed(data.length)
            piecesRcvd(idx) = true
            for ((b, i) ← data.zipWithIndex) {
                val byteIdx = idx * BYTES_PER_PIECE + i
                chunkData(byteIdx) = b
            }
        case ReceiveTimeout => chunkXferFailed
        case ChunkSuccess => writeChunk()
    }
}
