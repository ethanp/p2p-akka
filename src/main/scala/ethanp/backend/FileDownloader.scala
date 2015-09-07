package ethanp.backend

import java.io.File

import akka.actor._
import akka.event.LoggingReceive
import ethanp.backend.client._
import ethanp.file.{FileToDownload, LocalP2PFile}

import scala.collection.{BitSet, mutable}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.language.postfixOps

/**
 * Downloads a file from peers
 *
 * @param fileDLing the P2PFile that this FileDownloader is solely-responsible for downloading
 * @param downloadDir the directory in which the downloaded file will be saved
 */
class FileDownloader(fileDLing: FileToDownload, downloadDir: File) extends Actor with ActorLogging {
    import fileDLing.fileInfo._ // <- is *too* sneaky?

    /* fail if the file already exists */
    val localFile = new File(downloadDir, filename)
    if (localFile.exists()) {
        log error s"you already have $filename in your filesystem!"
        context stop self
    }

    var listeners = Set(context.parent)

    /* UTILITY METHODS */

    def fullMutableBitSet = mutable.BitSet(0 until numChunks: _*)
    def emptyMutableBitSet = new mutable.BitSet(numChunks)
    def peersWhoHaventResponded = potentialDownloadees -- liveSeederRefs -- liveLeecherRefs
    def liveSeederRefs = liveSeeders map (_.ref)
    def liveLeecherRefs = liveLeechers map (_.ref)

    /** @return BitSet containing "1"s for chunks that other peers are known to have */
    def availableChunks: BitSet =
        if (liveSeeders.nonEmpty) fullMutableBitSet
        else (liveLeechers foldLeft emptyMutableBitSet)(_|_.avbl)

    def randomPeerOwningChunk(idx: Int): FilePeer = {
        val validLeechers = liveLeechers filter (_ hasChunk idx)
        val validPeers = (liveSeeders ++ validLeechers).toVector
        val peerIdx = util.Random.nextInt(validPeers.size)
        validPeers(peerIdx)
    }

    /**
     * Spawn a ChunkDownloader to download this FileDownloader's File
     *
     * @param chunkIdx which chunk index to download
     * @param peerRef which ActorRef to download from
     */
    def spawnChunkDownloader(chunkIdx: Int, peerRef: ActorRef): ActorRef = {
        context.actorOf(
            Props(classOf[ChunkDownloader], p2PFile, chunkIdx, peerRef),
            name = s"chunk-$chunkIdx"
        )
    }


    /* CONFIGURATION */

    var maxConcurrentChunks = 3
    var progressTimeout = 4 seconds

    /**
     * called by Akka framework when this Actor is asynchronously started
     *
     * "Ping" everyone the Tracker told us is 'involved with' this file
     *
     * Each Client will respond appropriately with
     *      1. Seeding
     *      2. Leeching(avblty)
     *      3. PeerSideError("file with that hash not known")
     */
    override def preStart(): Unit = potentialDownloadees foreach (_ ! Ping(abbreviation))

    // SOMEDAY this should de-register me from all the event buses I'm subscribed to
    override def postStop(): Unit = () // this is the default

    /* FIELDS */

    val p2PFile = LocalP2PFile.empty(fileDLing.fileInfo, localFile)

    /** peers we've timed-out upon recently */
    var quarantine = Set.empty[ActorRef]

    // SOMEDAY should updated periodically after new queries of the Trackers
    var potentialDownloadees: Set[ActorRef] = fileDLing.seeders ++ fileDLing.leechers

    /** added to by responses from the peers when they get Ping'd */
    var liveSeeders = Set.empty[Seeder]
    var liveLeechers = Set.empty[Leecher]

    /** children actors who are supposed to be busy downloading chunks */
    val chunkDownloaders = mutable.Set.empty[ActorRef]

    /** check-lists of what needs to be done */
    def incompleteChunks = p2PFile.unavailableChunkIndexes
    val notStartedChunks = fullMutableBitSet

    def attemptChunkDownload(): Unit = {
        // kick-off an unstarted chunk
        if (notStartedChunks.nonEmpty) {
            if (chunkDownloaders.size >= maxConcurrentChunks) return
            val chunks = availableChunks
            (notStartedChunks & chunks).headOption match {
                case Some(nextIdx) =>
                    notStartedChunks.remove(nextIdx)
                    val peer = nextToDLFrom(nextIdx)
                    chunkDownloaders += spawnChunkDownloader(chunkIdx = nextIdx, peerRef = peer.ref)
                case None =>
                    // SOMEDAY I need to wait for an event published on the bus that a chunk has
                    // been downloaded and ask trackers for new people, and ping the dead people
                    // again periodically to see if they've woken up
                    log warning "none of the chunks remaining are available :("
            }

        }
        else if (incompleteChunks.nonEmpty) {
            log info s"all chunks started, waiting on ${incompleteChunks.size} transfers to complete"
        } else {
            /* transfer is complete */
            speedometer.cancel() // may be unnecessary since I'm poisoning myself anyway
            log warning s"transfer of $filename complete!"
            context.parent ! DownloadSuccess(filename)
            self ! PoisonPill
        }
    }

    // I could also use the priority scores (which Seeders & Leechers have) instead
    // but I haven't implemented transfer speed updating
    def nextToDLFrom(nextIdx: Int): FilePeer = randomPeerOwningChunk(nextIdx)

    /* speed calculations */
    var bytesDLedPastSecond = 0
    val speedometer = context.system.scheduler.schedule(initialDelay = 1 second, interval = 1 second) {
        bytesDLedPastSecond = 0
    }

    override def receive: Actor.Receive = LoggingReceive {

        case ReceiveTimeout => listeners foreach (_ ! TransferTimeout)

        case ChunkComplete(idx) =>
            context.setReceiveTimeout(progressTimeout)
            attemptChunkDownload()
            // SOMEDAY publish completion to EventBus
            // so that interested peers know we now have this chunk

        // this is (supposedly) received *after* the ChunkDownloader tried retrying a few times
        case ChunkDLFailed(idx, peerRef, cause) =>
            // SOMEDAY update the FilePeer object
            // SOMEDAY then try again iff it was just a timeout

        // comes from ChunkDownloader
        case DownloadSpeed(numBytes) =>
            bytesDLedPastSecond += numBytes
            // SOMEDAY update the peer-specific transfer speed (for dl priority) on the FilePeer object

        /**
         * This comes from this node's Client actor who wants to know how much of the file is complete.
         * Tell her we have everything, except for the incompleteChunks.
         */
        case Ping(abbrev) => if (abbrev == abbreviation) {
            val avblChunks: BitSet = fullMutableBitSet &~ incompleteChunks
            sender ! avblChunks
        }

        case Seeding =>
            liveSeeders += Seeder(sender())
            attemptChunkDownload()

        case Leeching(avblty) =>
            /* SOMEDAY use the event bus to subscribe to leecher's avblty update stream
                val bus = ActorEventBus()       // or however you do it
                bus.subscribe(this, sender())   // or however you do it

               This would be a "push" model, though of course we could also use a "pull" model...
                I think that might be more difficult to implement but also more efficient
             */
            liveLeechers += Leecher(fullMutableBitSet & avblty, sender()) /* the & is to convert immutable -> mutable */
            attemptChunkDownload()
    }
}
