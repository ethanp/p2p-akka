package ethanp.firstVersion

import akka.actor.{Props, Actor, ActorLogging, PoisonPill}
import akka.contrib.throttle.Throttler.{SetTarget, Rate}
import akka.contrib.throttle.TimerBasedThrottler
import ethanp.file.LocalP2PFile

import scala.util.{Failure, Success}

/**
 * Ethan Petuchowski
 * 7/3/15
 */
class ChunkReplyer(localP2PFile: LocalP2PFile, replyRate: Rate) extends Actor with ActorLogging {
    def handleErrors: PartialFunction[Any, Unit] = {
        case e: Throwable =>
            log.error(
                s"""
                  |$e
                  |couldn't read file ${localP2PFile.fileInfo.filename}
                  |ignoring client request
                 """.stripMargin
            )
        // don't exit or anything. just keep trucking.
    }

    def sendChunk(m: ReplyTo): Unit = {
        var pieceIdx = 0
        val piecesThisChunk = localP2PFile.fileInfo.numPiecesInChunk(m.chunkIdx)
        var hasntFailed = true

        /**
         * FROM: http://doc.akka.io/docs/akka/snapshot/contrib/throttle.html
         *
         * NOTE: This could be easily changed to a single global max upload
         *       throttle by just passing the reference to a SINGLE throttler
         *       to each ChunkReplyer in the constructor instead of having
         *       each ChunkReplyer create its OWN throttler. However, I think
         *       THIS is the way µTorrent does it, so I'm doing this for now.
         */
        val uploadThrottler = context.actorOf(Props(classOf[TimerBasedThrottler], replyRate))
        uploadThrottler ! SetTarget(Some(m.requester)) // NOTE: given-pattern to set attribute of remote actor

        def done: Boolean = pieceIdx == piecesThisChunk
        while (!done && hasntFailed) {
            localP2PFile.getPiece(m.chunkIdx, pieceIdx) match {
                case Success(arr) =>
                    // doesn't need to pass fileInfo bc it's being sent to
                    // particular ChunkDownloader
                    uploadThrottler ! Piece(arr, pieceIdx)
                    pieceIdx += 1
                case Failure(e) =>
                    log.error("request failed with " + e.getClass)
                    hasntFailed = false
            }
        }
        if (done && hasntFailed) {
            uploadThrottler ! ChunkSuccess // tells requester chunk done downloading
        }
    }

    override def receive: Receive = {
        case m : ReplyTo =>
            try sendChunk(m)
            catch handleErrors
            finally self ! PoisonPill
    }
}
