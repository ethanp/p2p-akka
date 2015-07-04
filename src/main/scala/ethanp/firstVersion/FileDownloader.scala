package ethanp.firstVersion

import java.io.File

import akka.actor._
import akka.event.LoggingReceive
import ethanp.file.{FileToDownload, LocalP2PFile}

import scala.collection.{BitSet, mutable}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.language.postfixOps

class FileDownloader(fileDLing: FileToDownload, downloadDir: File) extends Actor with ActorLogging {
    import fileDLing.fileInfo._ // <- that's really cool

    val localFile = new File(downloadDir, filename)
    if (localFile.exists()) {
        log.error(s"you already have $filename in your filesystem!")
        context.stop(self) // instantaneous self-immolation
    }

    // this is "shared-nothing", so I don't think local vars need to be `private`?
    val p2PFile = LocalP2PFile(fileDLing.fileInfo, localFile, unavbl = fullMutableBitSet)

    def fullMutableBitSet: mutable.BitSet = mutable.BitSet(0 until numChunks: _*)

    var maxConcurrentChunks = 3

    /** peers we've timed-out upon recently */
    var quarantine = Set.empty[ActorRef]

    // will be updated periodically after new queries of the trackers
    var potentialDownloadees: Set[ActorRef] = fileDLing.seeders ++ fileDLing.leechers

    def nonResponsiveDownloadees = potentialDownloadees -- liveSeederRefs -- liveLeecherRefs

    // added to by responses from the peers when they get Ping'd
    var liveSeeders = Set.empty[Seeder]
    var liveLeechers = Set.empty[Leecher]

    def liveSeederRefs = liveSeeders map (_.ref)
    def liveLeecherRefs = liveLeechers map (_.ref)

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

    case class Leecher(actorRef: ActorRef, val avbl: mutable.BitSet) extends FilePeer(actorRef) {
        override def hasChunk(idx: Int) = avbl contains idx
    }

    val chunkDownloaders = mutable.Set.empty[ActorRef]

    val incompleteChunks = fullMutableBitSet // starts out as all ones
    val notStartedChunks = fullMutableBitSet

    /**
     * @return BitSet containing "1"s for chunks that other peers are known to have
     */
    def availableChunks: BitSet = {
        val allAvbl = fullMutableBitSet.toImmutable
        if (liveSeeders.nonEmpty) allAvbl
        else (liveLeechers foldLeft allAvbl)(_ & _.avbl)  // I think I've found a monad
    }

    def maybeStartChunkDownload(): Unit = {

        // kick-off an unstarted chunk
        if (notStartedChunks.nonEmpty) {
            if (chunkDownloaders.size >= maxConcurrentChunks) return
            (notStartedChunks & availableChunks).headOption match {
                case Some(nextIdx) =>
                    notStartedChunks.remove(nextIdx)
                    downloadChunkFrom(nextIdx, nextToDLFrom(nextIdx))
                case None =>
                    // TODO I need to wait for an event published on the bus that a chunk has
                    // been downloaded and ask trackers for new people, and ping the dead people
                    // again periodically to see if they've woken up
                    log warning "none of the chunks remaining are available :("
            }

        }
        else if (incompleteChunks.nonEmpty) {
            log info s"all chunks started, waiting on ${incompleteChunks.size} transfers to complete"
        } else {
            speedometer.cancel() // may be unnecessary since I'm poisoning myself anyway
            log warning s"transfer of $filename complete!"
            context.parent ! DownloadSuccess(filename)
            self ! PoisonPill
        }
    }

    // TODO haha this is a terrible algorithm, I think it does work though
    // I guess I was going to take the priority scores (which Seeders & Leechers have) into account
    def nextToDLFrom(nextIdx: Int): ActorRef = {
        if (liveSeeders.nonEmpty) liveSeeders.head.ref
        else if (liveLeechers.nonEmpty) liveLeechers.find(_ hasChunk nextIdx).get.ref
        else throw new RuntimeException("no one to dl from")
    }

    def downloadChunkFrom(chunkIdx: Int, peerRef: ActorRef): Unit = {
        chunkDownloaders += context.actorOf(
            Props(classOf[ChunkDownloader], p2PFile, chunkIdx, peerRef),
            name = s"chunk-$chunkIdx"
        )
    }

    /** called by Akka framework when this Actor is asynchronously started */
    override def preStart(): Unit = potentialDownloadees.foreach(_ ! Ping(abbreviation))

    /* speed calculations */
    var bytesDLedPastSecond = 0
    val speedometer = context.system.scheduler.schedule(1 second, 1 second) {
        bytesDLedPastSecond = 0
    }

    override def receive: Actor.Receive = LoggingReceive {
        case ChunkComplete(idx) =>
            incompleteChunks.remove(idx)
            maybeStartChunkDownload()
            // TODO publish completion to EventBus

        // this is received *after* the ChunkDownloader tried retrying a few times
        case ChunkDLFailed(idx, peerRef) =>
            // TODO update the FilePeer object
            // TODO then try again

        // comes from ChunkDownloader
        case DownloadSpeed(numBytes) => bytesDLedPastSecond += numBytes

        // this comes from this node's Client actor who wants to know how much of the file is still incomplete
        case Ping(abbrev) => if (abbrev == abbreviation) sender ! incompleteChunks.toImmutable

        case Seeding =>
            liveSeeders += Seeder(sender())
            maybeStartChunkDownload()

        case Leeching(unavblty) =>
            /* TODO use the event bus to subscribe to leecher's avblty update stream
                val bus = ActorEventBus()       // or however you do it
                bus.subscribe(this, sender())   // or however you do it

               This would be a "push" model, though of course we could also use a "pull" model...
                I think that might be more difficult to implement but also more efficient
             */
            liveLeechers += Leecher(sender(), fullMutableBitSet &~ unavblty)
            maybeStartChunkDownload()
    }
}
