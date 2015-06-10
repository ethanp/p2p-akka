package ethanp.firstVersion

import java.io.File

import akka.actor._
import akka.event.LoggingReceive
import ethanp.file.{FileToDownload, LocalP2PFile}
import ethanp.firstVersion.Master.NodeID

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

/** sure, this is simplistic, but making it better is future work. */
class FileDownloader(fileDLing: FileToDownload, downloadDir: File) extends Actor with ActorLogging {
    val filename = fileDLing.fileInfo.filename

    val localFile = new File(downloadDir, filename)
    if (localFile.exists()) {
        log.error(s"you already have $filename in your filesystem!")
        context.stop(self) // instantaneous self-immolation
    }

    val p2PFile = LocalP2PFile(fileDLing.fileInfo, localFile)

    /** peers we've timed-out upon recently */
    var quarantine = List.empty[PeerLoc]

    var seederMap: Map[NodeID, ActorRef] = fileDLing.swarm.seeders
    var chunkDownloads = List.empty[ActorRef] // ChunkDownloaders

    var seederNum = 0 // someday will wrap-around zero, bring it on
    def nextSeeder = {
        seederNum += 1
        seederMap.toList((seederNum % seederMap.size).abs)
    }

    var chunksComplete = new Array[Boolean](fileDLing.fileInfo.numChunks)
    var chunksInProgress = new Array[Boolean](fileDLing.fileInfo.numChunks)

    def addChunkDownload(): Unit = {
        // kick-off an unstarted chunk
        chunksInProgress.zipWithIndex.collectFirst {
            case (inProgress, idx) if !inProgress =>
                chunksInProgress(idx) = true
                idx → PeerLoc(nextSeeder)
        } match {
            case Some((nextChunkIdx, peerLoc)) => downloadChunkFrom(nextChunkIdx, peerLoc)

            // if transfer complete, tell parent (Client)
            case None if chunksComplete.forall(_ == true) =>
                speedometer.cancel()
                log.warning(s"transfer of $filename complete!")
                context.parent ! DownloadSuccess(filename)
                self ! PoisonPill

            case _ => log.info("just waiting on transfers to complete")
        }
    }

    def downloadChunkFrom(chunkIdx: Int, peerLoc: PeerLoc): Unit = {
        chunkDownloads ::= context.actorOf(
            Props(classOf[ChunkDownloader], p2PFile, chunkIdx, peerLoc),
            name = s"chunk-$chunkIdx"
        )
    }

    /** called by Akka framework when this Actor is asynchronously started */
    override def preStart(): Unit = (1 to 4).foreach(i => addChunkDownload())

    /* speed calculations */
    var bytesDLedPastSecond = 0
    val speedometer = context.system.scheduler.schedule(1.second, 1.second) {
        log.warning(f"current DL speed for $filename: ${bytesDLedPastSecond.toDouble / 1000}%.2f")
        bytesDLedPastSecond = 0
    }

    override def receive: Actor.Receive = LoggingReceive {
        case ChunkComplete(idx) =>
            chunksComplete(idx) = true
            addChunkDownload()

        // this is received *after* the ChunkDownloader tried retrying a few times
        case ChunkDLFailed(idx, peerLoc) =>
            seederMap -= peerLoc.peerID
            if (seederMap.nonEmpty) downloadChunkFrom(idx, PeerLoc(nextSeeder))
            else log.warning(s"$filename seederList is empty")

        case DownloadSpeed(numBytes) => bytesDLedPastSecond += numBytes // should be child-actor
    }
}
