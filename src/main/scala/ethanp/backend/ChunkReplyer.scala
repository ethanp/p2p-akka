package ethanp.backend

import akka.actor.{Actor, ActorLogging, PoisonPill, Props}
import akka.contrib.throttle.Throttler.{Rate, SetTarget}
import akka.contrib.throttle.TimerBasedThrottler
import ethanp.backend.client.{Piece, ReplyTo}
import ethanp.file.LocalP2PFile

import scala.util.{Failure, Success}

/**
 * Ethan Petuchowski
 * 7/3/15
 */
class ChunkReplyer(localP2PFile: LocalP2PFile, replyRate: Rate) extends Actor with ActorLogging {

    def logError(e: Throwable) = {
        log.error(s"""
                     |$e
                     |couldn't read file ${localP2PFile.fileInfo.filename}
                     |ignoring client request
                   """.stripMargin
        )
        // don't exit or anything. just keep trucking.
    }

    def sendChunk(m: ReplyTo): Unit = {
        val piecesThisChunk = localP2PFile.fileInfo.numPiecesInChunk(m.chunkIdx)

        /**
         * FROM: http://doc.akka.io/docs/akka/snapshot/contrib/throttle.html
         *
         * NOTE: This could be easily changed to a single global max upload
         *       throttle by just passing the reference to a SINGLE throttler
         *       to each ChunkReplyer in the constructor instead of having
         *       each ChunkReplyer create its OWN throttler. However, I think
         *       THIS is the way µTorrent does it, so I'm doing this for now.
         *
         * Having the Throttler be a child of this actor does not work because
         * this actor will be dead before the throttled messages will all have
         * been sent out.
         */
        val uploadThrottler = context.system.actorOf(Props(classOf[TimerBasedThrottler], replyRate))
        uploadThrottler ! SetTarget(Some(m.requester)) // NOTE: given-pattern to set attribute of remote actor

        for (piece ← 0 until piecesThisChunk) {
            localP2PFile.getPiece(m.chunkIdx, piece) match {
                case Success(arr) =>
                    uploadThrottler ! Piece(arr, piece)

                case Failure(e) =>
                    logError(e)
                    return // give up (silently) on sending chunk
            }
        }
        uploadThrottler ! ChunkSuccess // now requester knows it is done downloading chunk
    }

    override def receive: Receive = {
        case m : ReplyTo =>
            sendChunk(m)
            self ! PoisonPill
    }
}
