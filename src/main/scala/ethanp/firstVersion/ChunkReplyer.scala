package ethanp.firstVersion

import akka.actor.{Actor, ActorLogging, PoisonPill}
import ethanp.file.LocalP2PFile

import scala.util.{Failure, Success}

/**
 * Ethan Petuchowski
 * 7/3/15
 */
class ChunkReplyer(localP2PFile: LocalP2PFile) extends Actor with ActorLogging {
    def handleErrors: PartialFunction[Any, Unit] = {
        case e: Throwable =>
            log.error(e.toString)
            log.error(s"couldn't read file ${localP2PFile.fileInfo.filename}")
            log.error("ignoring client request")
        // don't exit or anything. just keep trucking.
    }

    def sendChunk(m: ReplyTo): Unit = {
        var pieceIdx = 0
        val piecesThisChunk = localP2PFile.fileInfo.numPiecesInChunk(m.chunkIdx)
        var hasntFailed = true
        def done: Boolean = pieceIdx == piecesThisChunk
        while (!done && hasntFailed) {
            localP2PFile.getPiece(m.chunkIdx, pieceIdx) match {
                case Success(arr) =>
                    // doesn't need to pass fileInfo bc it's being sent to
                    // particular ChunkDownloader
                    m.requester ! Piece(arr, pieceIdx)
                    pieceIdx += 1
                case Failure(e) =>
                    log.error("request failed with " + e.getClass)
                    hasntFailed = false
            }
        }
        if (done && hasntFailed) {
            m.requester ! ChunkSuccess // is this useful for anything?
        }
    }

    override def receive: Receive = {
        case m : ReplyTo =>
            try sendChunk(m)
            catch handleErrors
            finally self ! PoisonPill
    }
}
