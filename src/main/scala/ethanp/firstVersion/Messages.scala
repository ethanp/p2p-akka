package ethanp.firstVersion

import akka.actor.ActorRef
import akka.contrib.throttle.Throttler.Rate
import ethanp.file.{FileInfo, FileToDownload, Sha2}

import scala.collection.BitSet

/**
 * Ethan Petuchowski
 * 6/4/15
 */
case class LoadFile(pathString: String, name: String)
case class TrackerLoc(trackerPath: ActorRef)
case class ListTracker(trackerPath: ActorRef)
case class TrackerKnowledge(knowledge: List[FileToDownload])
case class InformTrackerIHave(fileInfo: FileInfo)
case class TrackerSideError(errorString: String)
case class ClientError(errorString: String)
case class PeerSideError(errorString: String)
case class SuccessfullyAdded(filename: String)
case class DownloadFile(filename: String)
case class DownloadFileFrom(trackerLoc: ActorRef, filename: String)
case class Piece(arr: Array[Byte], pieceIdx: Int)
sealed trait ChunkStatus extends Serializable
case class ChunkComplete(chunkIdx: Int) extends ChunkStatus
case class ChunkDLFailed(chunkIdx: Int, peerPath: ActorRef, cause: FailureMechanism) extends ChunkStatus
case class ChunkRequest(infoAbbrev: Sha2, chunkIdx: Int)
case object ChunkSuccess
case class DownloadSpeed(numBytes: Int)
case class DownloadSuccess(filename: String)
case class Ping(infoAbbrev: Sha2)
case object Seeding
case class Leeching(unavblty: BitSet)
case class ReplyTo(requester: ActorRef, chunkIdx: Int)
case class SetUploadLimit(rate: Rate)
case object AddMeAsListener

sealed trait FailureMechanism
case object InvalidData extends FailureMechanism
case object TransferTimeout extends FailureMechanism