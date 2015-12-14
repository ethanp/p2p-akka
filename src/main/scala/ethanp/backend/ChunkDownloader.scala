package ethanp.backend

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
class ChunkDownloader(p2PFile: LocalP2PFile, chunkIdx: Int, peerRef: ActorRef)
    extends Actor with ActorLogging {

    /* -- CONFIGURATION -- */

    val piecesRcvd = new Array[Boolean](p2PFile.fileInfo numPiecesInChunk chunkIdx)


    /* -- FIELDS -- */
    val chunkData = new Array[Byte](p2PFile.fileInfo numBytesInChunk chunkIdx)
    var RECEIVE_TIMEOUT = 5.seconds
    var listeners = Set(context.parent)


    /**
      * Upon booting up, the ChunkDownloader will request the Chunk from the Peer
      * and set a Timeout for the Peer's response.
      */
    override def preStart(): Unit = {

        /* The docs say:
         *
         *      Once set, the receive timeout stays in effect
         *      (i.e. continues firing repeatedly after inactivity periods).
         *      Pass in `Duration.Undefined` to switch off this feature.
         *
         * This means we don't need to set another timeout after every message.
         */
        context.setReceiveTimeout(RECEIVE_TIMEOUT)

        peerRef ! ChunkRequest(p2PFile.fileInfo.abbreviation, chunkIdx)
    }

    override def receive: Actor.Receive = LoggingReceive {

        // SOMEDAY inform listeners for bandwidth calculation
        case Piece(pieceIdx, data) =>
            piecesRcvd(pieceIdx) = true
            saveData(data, pieceIdx)

        // triggered by context.setReceiveTimeout above
        case ReceiveTimeout =>
            chunkXferFailed(cause = TransferTimeout)

        /*
         * According to the current [implementation of the] protocol,
         * the Peer sends a `ChunkSuccess` after sending ALL `Piece`s belonging to `this.Chunk`.
         *
         * The fact that this arrives properly in-order wrt the `Piece`s is due to Akka's
         * built-in "guaranteed FIFO-ordering on messages between 2 particular Nodes".
         *
         * I don't see why this would be necessary, and maybe it will cause problems, but I
         * think it could also come in handy...
         *  e.g. for registering callbacks somewhere onChunkSuccess?
         * so I'm leaving it in here for now.
         */
        case ChunkSuccess =>
            def dataAlreadyWritten = p2PFile hasDataForChunk chunkIdx
            def invalidChunkHash = (Sha2 hashOf chunkData) != p2PFile.fileInfo.chunkHashes(chunkIdx)

            // precondition: data could already be there because another ChunkDownloader wrote it
            if (dataAlreadyWritten) {
                log warning s"data for chunk $chunkIdx for ${p2PFile.file.getName} was already written"
            }
            else if (invalidChunkHash) {
                log error s"data for chunk $chunkIdx for ${p2PFile.file.getName} was invalid"
                chunkXferFailed(cause = InvalidData)
            }
            else chunkXferSuccess()

        case AddMeAsListener =>
            listeners += sender
    }

    def chunkXferSuccess() = notifyListenersAndDie(ChunkCompleteData(chunkIdx, chunkData))

    def chunkXferFailed(cause: FailureMechanism) = notifyListenersAndDie(ChunkDLFailed(chunkIdx, peerRef, cause))

    /* -- ACTOR BEHAVIOR -- */

    def notifyListenersAndDie(msg: ChunkStatus) {
        listeners foreach (_ ! msg)
        self ! PoisonPill
    }

    def saveData(data: Array[Byte], pieceIdx: Int) = {
        for ((b, i) ← data.zipWithIndex) {
            chunkData(pieceIdx * BYTES_PER_PIECE + i) = b
        }
    }
}

object ChunkDownloader {
    def props(p2PFile: LocalP2PFile, chunkIdx: Int, peerRef: ActorRef) =
        Props(new ChunkDownloader(p2PFile, chunkIdx, peerRef))
}
