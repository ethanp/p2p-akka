package ethanp.backend

import java.io.{File, RandomAccessFile}

import akka.actor._
import akka.event.LoggingReceive
import ethanp.backend.client._
import ethanp.file.LocalP2PFile.BYTES_PER_CHUNK
import ethanp.file.{FileToDownload, LocalP2PFile}

import scala.collection.{BitSet, mutable}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.language.postfixOps

/** Downloads a file from peers.
  *
  * The actor crashes on startup if a file with the required name already exists.
  *
  * @param fileDLing the P2PFile that this FileDownloader is solely-responsible for downloading
  * @param downloadDir the directory in which the downloaded file will be saved
  */
class FileDownloader(fileDLing: FileToDownload, downloadDir: File) extends Actor with ActorLogging {

    import fileDLing.fileInfo.{abbreviation, filename, numChunks}

    /** Reference to the file-handle (on the file-system) to which the chunks of the
      * file will be written.
      */
    val localFile = new File(downloadDir, filename)

    /* crash this actor if the file already exists */
    if (localFile.exists()) {
        log error s"you already have $filename in your filesystem!"
        context stop self
    }

    /** Reference to the file */
    val p2PFile = LocalP2PFile.empty(fileDLing.fileInfo, localFile)

    /** A writeable stream to the local file.
      *
      * One ''could'' change this method to (instead) pass `"rwd"` or `"rws"` flags,
      * which would mean we write ''synchronously''. That shouldn't be necessary though.
      */
    val fileWriter = new RandomAccessFile(p2PFile.file, "rw")


    /** add seeders who respond to Pings,
      * remove on timeout or receiving invalid data
      */
    var liveSeeders = Set.empty[Seeder]

    /** add leechers who respond to Pings,
      * remove on timeout or receiving invalid data
      */
    var liveLeechers = Set.empty[Leecher]

    /** peers we've timed-out upon or received invalid data from recently */
    var quarantine = Set.empty[FilePeer]

    /** children actors who are ''supposed'' to be busy downloading chunks */
    val chunkDownloaders = mutable.Set.empty[ActorRef]
    val notStartedChunks = createFullMutableBitSet

    /** for SOMEDAY speed calculations */
    var bytesDLedPastSecond = 0
    context.system.scheduler.schedule(initialDelay = 1 second, interval = 1 second) {
        bytesDLedPastSecond = 0
    }

    /** Notified of TransferTimeouts */
    var listeners = Set(context.parent)

    /** How many ChunkDownloader children this FileDownloader may have at any given time */
    var maxConcurrentChunks = 3

    /** Reset upon chunk download initiation */
    var retryDownloadInterval = 1 minute

    var downloadTimeoutTimer = createDownloadTimeoutTimer

    def createDownloadTimeoutTimer = context.system.scheduler.scheduleOnce(
        delay = retryDownloadInterval,
        receiver = self,
        message = ReceiveTimeout
    )

    def resetDownloadTimeoutTimer(): Unit = {
        downloadTimeoutTimer.cancel()
        downloadTimeoutTimer = createDownloadTimeoutTimer
    }

    // SOMEDAY should updated periodically after new queries of the Trackers
    var potentialDownloadees: Set[ActorRef] = fileDLing.seeders ++ fileDLing.leechers

    def peersWhoResponded = List(liveLeechers, liveSeeders, quarantine) flatMap (_ map (_ actorRef))

    def peersWhoHaventResponded = potentialDownloadees -- peersWhoResponded

    def liveSeederRefs = liveLeechers map (_.actorRef)

    def underlyingRefs[Pear <: FilePeer](set: Set[Pear]) = set map (_.actorRef)

    def quarantinePeer(actorRef: ActorRef): Unit = {
        for (peer <- liveLeechers if peer.actorRef == actorRef) {
            liveLeechers -= peer
            quarantine += peer
        }
        for (peer <- liveSeeders if peer.actorRef == actorRef) {
            liveSeeders -= peer
            quarantine += peer
        }
    }

    /** called by Akka framework when this Actor is asynchronously started
      *
      * "Ping" everyone the Tracker told us is 'involved with' this file
      *
      * Each Client will respond appropriately with
      * 1. Seeding
      * 2. Leeching(avblty)
      * 3. PeerSideError("file with that hash not known")
      */
    override def preStart(): Unit = potentialDownloadees foreach (_ ! GetAvblty(abbreviation))

    // SOMEDAY this should de-register me from all the event buses I'm subscribed to
    override def postStop(): Unit = fileWriter.close()

    override def receive: Actor.Receive = LoggingReceive {

        case ReceiveTimeout =>
        // TODO reconnect with everyone in swarm and check availabilities
        // This includes those in liveSeeders, liveLeechers, and the `quarantine`

        case ChunkCompleteData(idx, chunkData) =>
            writeChunkData(idx, chunkData)
            attemptChunkDownload()

        // SOMEDAY publish completion to EventBus
        // so that interested peers know we now have this chunk

        /** Received *after* the ChunkDownloader tried retrying a few times
          */
        case failureMessage@ChunkDLFailed(idx, peerRef, cause) => cause match {
            case TransferTimeout => listeners foreach (_ ! failureMessage)
            case InvalidData => log debug s"Received invalid data from $peerRef"
        }
            quarantinePeer(peerRef)

        // comes from ChunkDownloader
        case DownloadSpeed(numBytes) =>
            bytesDLedPastSecond += numBytes
        // SOMEDAY update the peer-specific transfer speed (for dl priority) on the FilePeer object

        /** This comes from this node's Client actor who wants to know how much of the file is complete.
          * Tell her we have everything, except for the incompleteChunks.
          */
        case GetAvblty(abbrev) => if (abbrev == abbreviation) {
            val avblChunks: BitSet = createFullMutableBitSet &~ incompleteChunks
            sender ! avblChunks
        }

        case Seeding =>
            liveSeeders += Seeder(sender())
            attemptChunkDownload()

        case Leeching(avblty) =>
            /* SOMEDAY use the event bus to subscribe to leecher's avblty update stream
                val bus = ActorEventBus()       // or however you do it
                bus.subscribe(this, sender())   // or however you do it
             */
            liveLeechers += Leecher(createFullMutableBitSet & avblty, sender()) /* the & is to convert immutable -> mutable */
            attemptChunkDownload()
    }

    /**
      * Write received chunkData to the local filesystem iff it hashes correctly.
      *
      * We assume that the `ChunkDownloader` already verified that the `chunkData` are ''correct''.
      *
      * Note: An IOException here will crash the program.
      * I don't really have any better ideas...retry?
      * I'll address it if it comes up.
      * <p/>
      * Since the chunkData sent to the file downloader to write (viz. ''here''), there can
      * (potentially) be multiple nodes from which this chunk is being concurrently downloaded,
      * and there are no write conflicts. Also this will mean that there's no shared mutable
      * state (viz. the local file) between the `ChunkDownloader` and its parent `FileDownloader`
      * actor.
      */
    def writeChunkData(chunkIdx: Int, chunkData: Array[Byte]): Boolean = {

        // maybe a previous ChunkDownloader sent us this data
        if (p2PFile hasDataForChunk chunkIdx)
            return false

        log debug s"writing out all ${chunkData.length} bytes of chunk $chunkIdx"

        // Seeking to offset beyond end-of-file ''does not'' change the file length.
        // Writing beyond end-of-file ''does'' change the file length.
        fileWriter.seek(chunkIdx * BYTES_PER_CHUNK)
        fileWriter.write(chunkData)

        /* in this way the chunk index is removed "atomically" from the perspective of the
         * file downloading process
         */
        p2PFile.unavailableChunkIndexes.remove(chunkIdx)
        true
    }

    def createFullMutableBitSet = mutable.BitSet(0 until numChunks: _*)

    /** Reset the file-level download timeout `retryDownloadInterval`.
      *
      * If there are chunks we still have to download, and there aren't enough
      * concurrent chunk downloads in-progress, find the FIRST available chunk
      * we still need and spawn a `ChunkDownloader` to retrieve it from the
      * NEXT PEER.
      *
      * (The "next peer" algorithm can be plugged in separately, currently it
      * randomly chooses a peer reportedly owning the chunk.)
      *
      * If we've finished downloading, inform the `listeners`, and take a
      * `PoisonPill`.
      */
    def attemptChunkDownload(): Unit = {
        resetDownloadTimeoutTimer()
        // kick-off an unstarted chunk
        if (notStartedChunks.nonEmpty) {
            if (chunkDownloaders.size >= maxConcurrentChunks) return
            (notStartedChunks & availableChunks).headOption match {
                case Some(nextIdx) =>
                    notStartedChunks.remove(nextIdx)
                    val peer = nextToDLFrom(nextIdx)
                    chunkDownloaders += spawnChunkDownloader(chunkIdx = nextIdx, peerRef = peer.actorRef)
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
            log warning s"transfer of $filename complete!"
            listeners foreach (_ ! DownloadSuccess(filename))
            self ! PoisonPill
        }
    }

    /** @return BitSet containing "1"s for chunks that other peers are known to have */
    def availableChunks: BitSet =
        if (liveSeeders.nonEmpty) createFullMutableBitSet
        else (liveLeechers foldLeft emptyMutableBitSet) (_ | _.avbl)

    def emptyMutableBitSet = new mutable.BitSet(numChunks)

    /** Spawn a ChunkDownloader to download this FileDownloader's File
      *
      * @param chunkIdx which chunk index to download
      * @param peerRef which ActorRef to download from
      */
    def spawnChunkDownloader(chunkIdx: Int, peerRef: ActorRef): ActorRef =
        context.actorOf(
            ChunkDownloader.props(p2PFile, chunkIdx, peerRef),
            name = s"chunk-$chunkIdx"
        )

    /* SOMEDAY use the priority scores (which Seeders & Leechers have) instead
       but I haven't implemented transfer speed updating */

    /** configurable "next peer" algorithm, currently downloads from a random peer
      * owning the chunk at the given `nextIdx` */
    def nextToDLFrom(chunkIdx: Int): FilePeer = randomPeerOwningChunk(chunkIdx)

    def randomPeerOwningChunk(chunkIdx: Int): FilePeer = {
        val validLeechers = liveLeechers filter (_ hasChunk chunkIdx)
        val validPeers = (liveSeeders ++ validLeechers).toVector
        val peerIdx = util.Random.nextInt(validPeers.size)
        validPeers(peerIdx)
    }

    /** Chunk indices that have not been written to disk yet.
      * There may be outstanding `ChunkDownloader`s for these indices.
      */
    def incompleteChunks: BitSet = p2PFile.unavailableChunkIndexes.toImmutable
}

object FileDownloader {
    def props(fileToDownload: FileToDownload, downloadDir: File) =
        Props(new FileDownloader(fileToDownload, downloadDir))
}
