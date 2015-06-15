package ethanp.firstVersion

import java.io.File

import akka.actor._
import akka.event.LoggingReceive
import ethanp.file.{FileToDownload, LocalP2PFile}

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class FileDownloader(fileDLing: FileToDownload, downloadDir: File) extends Actor with ActorLogging {
    val filename = fileDLing.fileInfo.filename
    val numChunks = fileDLing.fileInfo.numChunks

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

    val chunkDownloaders = mutable.Set.empty[ActorRef]

    var nextPosInt = 0
    def nextSeeder: ActorRef = {
        nextPosInt = (nextPosInt + 1) % Integer.MAX_VALUE
        liveSeeders.toList(nextPosInt % liveSeeders.size)
    }

    val incompleteChunks = mutable.BitSet(1 to numChunks:_*)
    val notStartedChunks = mutable.BitSet(1 to numChunks:_*)

    def addChunkDownload(): Unit = {
        // kick-off an unstarted chunk
        if (notStartedChunks.nonEmpty) {
            val nextIdx = notStartedChunks.head
            notStartedChunks.remove(nextIdx)
            downloadChunkFrom(nextIdx, nextSeeder)
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

    def downloadChunkFrom(chunkIdx: Int, peerRef: ActorRef): Unit = {
        chunkDownloaders += context.actorOf(
            Props(classOf[ChunkDownloader], p2PFile, chunkIdx, peerRef),
            name = s"chunk-$chunkIdx"
        )
    }

    /** called by Akka framework when this Actor is asynchronously started */
    override def preStart(): Unit = {
        // TODO should be pinging potentialDownloadees instead!
        (1 to 4).foreach(i => addChunkDownload())
    }

    /* speed calculations */
    var bytesDLedPastSecond = 0
    val speedometer = context.system.scheduler.schedule(1.second, 1.second) {
        log.warning(f"current DL speed for $filename: ${bytesDLedPastSecond.toDouble / 1000}%.2f")
        bytesDLedPastSecond = 0
    }

    override def receive: Actor.Receive = LoggingReceive {
        case ChunkComplete(idx) =>
            incompleteChunks(idx) = true
            addChunkDownload()

        // this is received *after* the ChunkDownloader tried retrying a few times
        case ChunkDLFailed(idx, peerRef) =>
            liveSeeders -= peerRef
            if (liveSeeders.nonEmpty) downloadChunkFrom(idx, nextSeeder)
            else log.warning(s"$filename seederList is empty")

        case DownloadSpeed(numBytes) => bytesDLedPastSecond += numBytes // should be child-actor
    }
}
