package ethanp.backend

import akka.actor.ActorRef

import scala.collection.mutable

/**
  * Ethan Petuchowski
  * 7/4/15
  */
/** They're ordered by how desirable they are to download from */
sealed abstract class FilePeer(val ref: ActorRef) extends Ordered[FilePeer] {
    val UNKNOWN = -1
    var histSpeedBytesSec: Int = UNKNOWN
    var ongoingChunkTransfers = List.empty[ChunkDownloader]

    def hasChunk(idx: Int): Boolean

    // must ensure that lower == HIGHER priority
    def priorityScore: Int =
        if (ongoingChunkTransfers.size > 5) 0
        else -histSpeedBytesSec

    // Still naive? Here, we satisfy transitivity, reflexivity, etc. This must be preserved.
    override def compare(that: FilePeer): Int = {
        val CHOOSE_ME = -1
        val TIE = 0
        val CHOOSE_THEM = 1
        (histSpeedBytesSec, that.histSpeedBytesSec) match {
            case (UNKNOWN, UNKNOWN) => TIE
            case (UNKNOWN, _) => CHOOSE_ME
            case (_, UNKNOWN) => CHOOSE_THEM
            case _ => priorityScore - that.priorityScore
        }
    }
}

case class Seeder(actorRef: ActorRef) extends FilePeer(actorRef) {
    override def hasChunk(idx: Int): Boolean = true
}

case class Leecher(avbl: mutable.BitSet, actorRef: ActorRef) extends FilePeer(actorRef) {
    override def hasChunk(idx: Int) = avbl contains idx
}
