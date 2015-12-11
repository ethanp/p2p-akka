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
class ChunkDownloader(p2PFile: LocalP2PFile, chunkIdx: Int, peerRef: ActorRef)
    extends Actor with ActorLogging {

    /* -- CONFIGURATION -- */

    var RECEIVE_TIMEOUT = 2.seconds


    /* -- FIELDS -- */

    val piecesRcvd = new Array[Boolean](p2PFile.fileInfo numPiecesInChunk chunkIdx)
    val chunkData = new Array[Byte](p2PFile.fileInfo numBytesInChunk chunkIdx)
    var listeners = Set(context.parent)


    /* -- METHODS -- */

    /**
      * Write the downloaded Chunk to the local filesystem iff it hashes correctly.
      *
      * Note: An IOException here will crash the program. I don't really have any better ideas...retry?
      * I'll address it if it comes up
      */
    // TODO this is actually, problematic! the chunkData should be sent to the file downloader
    // to write (by this method), so that there can (potentially) be multiple nodes from which
    // this chunk is being concurrently downloaded, and there are no write conflicts. Also this
    // will mean that there's no shared state (the local file) between this actor and its parent
    // `FileDownloader` object.
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

    def notifyListenersAndDie(msg: ChunkStatus) {
        listeners foreach (_ ! msg)
        self ! PoisonPill
    }

    def chunkXferSuccess() = notifyListenersAndDie(ChunkComplete(chunkIdx))

    def chunkXferFailed(cause: FailureMechanism) = notifyListenersAndDie(ChunkDLFailed(chunkIdx, peerRef, cause))

    def saveData(data: Array[Byte], pieceIdx: Int) = {
        for ((b, i) ← data.zipWithIndex) {
            chunkData(pieceIdx * BYTES_PER_PIECE + i) = b
        }
    }

    /* -- ACTOR BEHAVIOR -- */

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

        case Piece(pieceIdx, data) =>
            //            listeners.foreach(_ ! DownloadSpeed(data.length))
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
            val success = writeChunk()
            if (success) chunkXferSuccess()
            else chunkXferFailed(cause = InvalidData)

        case AddMeAsListener =>
            listeners += sender
    }
}
