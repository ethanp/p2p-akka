package ethanp.firstVersion

import java.io.RandomAccessFile

import akka.actor.{PoisonPill, ReceiveTimeout, ActorLogging, Actor}
import akka.event.LoggingReceive
import ethanp.file.LocalP2PFile
import ethanp.file.LocalP2PFile._

import scala.concurrent.duration._

/**
 * Ethan Petuchowski
 * 6/6/15
 */
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
