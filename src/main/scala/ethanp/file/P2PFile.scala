package ethanp.file

import java.io.File

import akka.actor.ActorPath
import ethanp.common.Sha2

import scala.collection.mutable

/**
 * Ethan Petuchowski
 * 6/3/15
 *
 * The goal is that a file never exists fully in memory
 * It is a collection of "chunks"
 * Those "chunks" get transmitted in batches of "pieces"
 * Each "piece" is transmitted as an akka message
 *      This is when the progress bar may be updated
 */

/**
 * The P2PFile contains metadata about a file that a peer requires
 *      to be able to up/download the file
 * It is saved on disk (as JSON?)
 */
trait P2PFile {
    val numChunks: Int
    val chunkHashes: Array[Sha2]
    val fileHash: Sha2
}

/**
 * sent through akka by the Tracker, when a file is requested
 */
case class FileToDownload(
    numChunks     : Int,
    chunkHashes   : Array[Sha2],
    fileHash      : Sha2
)
extends P2PFile
{
    val peersThoughtToOwnFile = mutable.HashSet.empty[ActorPath]
    val trackersWithKnowledge = mutable.HashSet.empty[ActorPath]
}

case class LocalP2PFile(
    /** where the actual data for this file resides on the local file system */
    localFileLoc    : File,
    metadataFileLoc : File,
    numChunks       : Int,
    chunkHashes     : Array[Sha2],
    fileHash        : Sha2
)
extends P2PFile
