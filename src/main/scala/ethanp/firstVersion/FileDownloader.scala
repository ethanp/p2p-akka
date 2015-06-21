package ethanp.firstVersion

import java.io.File

import akka.actor._
import akka.event.LoggingReceive
import ethanp.file.{FileToDownload, LocalP2PFile}

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class FileDownloader(fileDLing: FileToDownload, downloadDir: File) extends Actor with ActorLogging {
    import fileDLing.fileInfo._ // <- that's really cool

    val localFile = new File(downloadDir, filename)
    if (localFile.exists()) {
        log.error(s"you already have $filename in your filesystem!")
        context.stop(self) // instantaneous self-immolation
    }

    // this is "shared-nothing", so I don't think local vars need to be `private`?
    val p2PFile = LocalP2PFile(fileDLing.fileInfo, localFile)

    /** peers we've timed-out upon recently */
    var quarantine = Set.empty[ActorRef]

    // will be updated periodically after new queries of the trackers
    val potentialDownloadees: Set[ActorRef] = fileDLing.seeders ++ fileDLing.leechers

    var liveSeeders: Set[ActorRef] = fileDLing.seeders // TODO start out empty and fill with Pings

    var livePeers = Vector.empty[FilePeer] // TODO use livePeers.max to get the next one to DL from

    /** They're ordered by how desirable they are to download from */
    sealed abstract class FilePeer(val actorRef: ActorRef) extends Ordered[FilePeer] {
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

    class Seeder(actorRef: ActorRef) extends FilePeer(actorRef) {
        override def hasChunk(idx: Int): Boolean = true
    }

    class Leecher(actorRef: ActorRef) extends FilePeer(actorRef) {
        val avblChunks = new mutable.BitSet(numChunks) // starts out as all zeros
        override def hasChunk(idx: Int): Boolean = avblChunks.contains(idx)
    }

    val chunkDownloaders = mutable.Set.empty[ActorRef]


    val incompleteChunks = mutable.BitSet(1 to numChunks:_*) // starts out as all ones
    val notStartedChunks = mutable.BitSet(1 to numChunks:_*)

    def addChunkDownload(): Unit = {
        // kick-off an unstarted chunk
        if (notStartedChunks.nonEmpty) {
            val nextIdx = notStartedChunks.head
            notStartedChunks.remove(nextIdx)
            downloadChunkFrom(nextIdx, nextToDLFrom(nextIdx))
        }
        else if (incompleteChunks.nonEmpty) {
            log.info("just waiting on transfers to complete")
        } else {
            speedometer.cancel() // may be unecessary since I'm poisoning myself anyway
            log.warning(s"transfer of $filename complete!")
            context.parent ! DownloadSuccess(filename)
            self ! PoisonPill
        }
    }

    def nextToDLFrom(nextIdx: Int): ActorRef = livePeers.filter(_ hasChunk nextIdx).max.actorRef

    def downloadChunkFrom(chunkIdx: Int, peerRef: ActorRef): Unit = {
        chunkDownloaders += context.actorOf(
            Props(classOf[ChunkDownloader], p2PFile, chunkIdx, peerRef),
            name = s"chunk-$chunkIdx"
        )
    }

    /** called by Akka framework when this Actor is asynchronously started */
    override def preStart(): Unit = {
        potentialDownloadees.foreach(_ ! Ping(abbreviation))
    }

    /* speed calculations */
    var bytesDLedPastSecond = 0
    val speedometer = context.system.scheduler.schedule(1.second, 1.second) {
        log.warning(f"current DL speed for $filename: ${bytesDLedPastSecond.toDouble / 1000}%.2f")
        bytesDLedPastSecond = 0
    }

    override def receive: Actor.Receive = LoggingReceive {
        case ChunkComplete(idx) =>
            incompleteChunks.remove(idx)
            addChunkDownload()

        // this is received *after* the ChunkDownloader tried retrying a few times
        case ChunkDLFailed(idx, peerRef) =>
            liveSeeders -= peerRef
            // TODO update the FilePeer object
            if (liveSeeders.nonEmpty) downloadChunkFrom(idx, nextToDLFrom(idx))
            else log.warning(s"$filename seederList is empty")

        case DownloadSpeed(numBytes) => bytesDLedPastSecond += numBytes // should be child-actor

        // TODO this is where we should be calling addChunkDownload()
        case Ping(_) => ???
    }
}
