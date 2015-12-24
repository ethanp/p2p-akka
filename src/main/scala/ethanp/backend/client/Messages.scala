package ethanp.backend.client

import akka.actor.ActorRef
import akka.contrib.throttle.Throttler.Rate
import ethanp.file.{FileInfo, FileToDownload, Sha2}

import scala.collection.BitSet

/**
  * Ethan Petuchowski
  * 6/4/15
  */

/* -- CLI COMMANDS -- */
/* these commands have the sole purpose of */
/* better testing that the program actually works */

case class LoadFile(pathString: String, name: String)

case class TrackerLoc(trackerRef: ActorRef)

case class DownloadFileFrom(trackerLoc: ActorRef, filename: String)


/* -- PROTOCOL COMMANDS -- */

/** Request from Client to Tracker to ask what files it has
  */
case class ListTracker(trackerRef: ActorRef)

/** Response from Tracker to Client after receiving ListTracker
  */
case class TrackerKnowledge(knownFiles: List[FileToDownload])

/** Client tells Tracker that it is Seeding the file with the given `FileInfo`
  *
  * Tracker adds the Client to the list of that file's Seeders
  */
case class InformTrackerIHave(fileInfo: FileInfo)

/** Tracker couldn't perform the requested action
  */
case class TrackerSideError(errorString: String)

/** Client couldn't perform the requested action
  */
case class ClientError(errorString: String)

/** Peer couldn't perform the request from Client
  */
case class PeerSideError(errorString: String)

/** Tracker added Client to list of Seeders of given `FileInfo`
  *
  * Client forwards this to each of its `listeners`
  */
case class SuccessfullyAdded(filename: String)

/** Sent from Client to Tracker in request for a `FileToDownload`
  * containing the Trackers knowledge of Peers hosting the requested file
  */
case class DownloadFile(filename: String)

/** An ordered segment of a Chunk
  */
case class Piece(pieceIdx: Int, data: Array[Byte])

/** Request from Client to Peer for a `Chunk` (which will arrive in `Piece`s)
  */
case class ChunkRequest(infoAbbrev: Sha2, chunkIdx: Int)

/** Sent from ChunkReplyer to Client after sending all Pieces
  *
  * Listeners may register a callback upon Client's reception of this message
  */
case object ChunkSuccess

/* SOMEDAY DownloadSpeed is not implemented */
/** ChunkDownloader informs Listeners (e.g. FileDownloader)
  * the number of bytes received, upon receipt of a Piece
  */
case class DownloadSpeed(numBytes: Int)

/** FileDownloader informs Client that file is done downloading
  */
case class DownloadSuccess(filename: String)

/** Client pings Peers to ask for their Chunk availability BitSets
  */
case class GetAvblty(infoAbbrev: Sha2)

/** Seeding Peer informs Client that it has the whole file
  */
case object Seeding

/** Leeching Peer informs Client which Chunks it owns (so far)
  */
case class Leeching(avblty: BitSet)


case class ChunkReply(requester: ActorRef, chunkIdx: Int)

case class SetUploadLimit(rate: Rate)

case object AddMeAsListener

sealed trait ChunkStatus extends Serializable

/** Sent from ChunkDownloader to FileDownloader once the chunk data has been
  * received from the peer
  */
case class ChunkCompleteData(chunkIdx: Int, chunkData: Array[Byte]) extends ChunkStatus

case class ChunkDLFailed(chunkIdx: Int, peerPath: ActorRef, cause: FailureMechanism)
    extends ChunkStatus

sealed trait FailureMechanism

case object InvalidData extends FailureMechanism

case object TransferTimeout extends FailureMechanism
