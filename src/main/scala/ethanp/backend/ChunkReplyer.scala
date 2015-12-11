package ethanp.backend

import akka.actor.{Actor, ActorLogging, PoisonPill, Props}
import akka.contrib.throttle.Throttler.{Rate, SetTarget}
import akka.contrib.throttle.TimerBasedThrottler
import ethanp.backend.client.{ChunkReply, ChunkSuccess, Piece}
import ethanp.file.LocalP2PFile

import scala.util.{Failure, Success}

/**
  * Ethan Petuchowski
  * 7/3/15
  */
class ChunkReplyer(localP2PFile: LocalP2PFile, replyRate: Rate) extends Actor with ActorLogging {

    def logError(e: Throwable) = {
        log.error(
            s"""
               |$e
               |couldn't read file ${localP2PFile.fileInfo.filename}
               |ignoring client request
                   """.stripMargin
        )
        // don't exit or anything. just keep trucking.
    }

    /**
      * Send the Client each Piece of the Chunk, and then a ChunkSuccess
      *
      * Simply give up if there's an Exception
      * (Client will timeout, and appropriate steps will be taken)
      */
    def sendChunk(reply: ChunkReply): Unit = {
        val numPieces = localP2PFile.fileInfo.numPiecesInChunk(reply.chunkIdx)

        /*
         * SOURCE: http://doc.akka.io/docs/akka/snapshot/contrib/throttle.html
         *
         * NOTE 1: We could also make a global throttler
         *
         * NOTE 2: Having the Throttler be a child of this actor does not work because
         *         this actor will be dead before the throttled messages will all have
         *         been sent out.
         */
        val uploadThrottler = context.system.actorOf(Props(classOf[TimerBasedThrottler], replyRate))
        uploadThrottler ! SetTarget(Some(reply.requester)) // NOTE: given-pattern to set attribute of remote actor

        /* send each piece */
        for (pieceIdx ← 0 until numPieces) {
            localP2PFile.getPiece(reply.chunkIdx, pieceIdx) match {
                case Success(pieceData) =>
                    uploadThrottler ! Piece(pieceIdx, pieceData)

                /* TODO test this */
                /* Give up if there's a failure */
                case Failure(throwable) =>
                    logError(throwable)
                    return // give up (silently) on sending chunk
            }
        }
        uploadThrottler ! ChunkSuccess // now requester knows it is done downloading chunk
    }

    override def receive: Receive = {
        case m: ChunkReply =>
            sendChunk(m)
            self ! PoisonPill
    }
}
