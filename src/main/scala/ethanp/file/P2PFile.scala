package ethanp.file

import java.io.{BufferedInputStream, File, FileInputStream}
import java.security.MessageDigest

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
//    metadataFileLoc : File, (for later...)
    numChunks       : Int,
    chunkHashes     : Array[Sha2],
    fileHash        : Sha2
)
extends P2PFile

object LocalP2PFile {

    val BYTES_PER_PIECE = 4
    val PIECES_PER_CHUNK = 4
    val BYTES_PER_CHUNK = BYTES_PER_PIECE * PIECES_PER_CHUNK

    def hashTheFile(file: File): (Array[Sha2], Sha2) = {
        val fis = new BufferedInputStream(new FileInputStream(file))
        val readArr = new Array[Byte](BYTES_PER_CHUNK)
        val fileDigester = MessageDigest.getInstance("SHA-256")
        val len = file.length()
        var bytesRead = 1
        var offset = 0
        var totalRead = 0
        val chunkHashes = mutable.MutableList.empty[Sha2]
        def doneReading = totalRead == len
        def filledReadArray = offset + bytesRead == readArr.length
        def updateHashes() {
            fileDigester.update(readArr)
            chunkHashes += Sha2.hashOf(readArr)
            offset = 0
        }
        try {
            while (!doneReading) {
                bytesRead = fis.read(readArr, offset, readArr.length - offset)
                totalRead += bytesRead
                if (filledReadArray || doneReading) {
                    updateHashes()
                } else {
                    offset += bytesRead
                }
            }
        } catch { case e: Exception ⇒
            System.err.println(e.getMessage)
            e.printStackTrace()
            System exit 5
        } finally {
            fis.close()
        }
        (chunkHashes.toArray, Sha2.digestToBase64(fileDigester.digest()))
    }

    def loadFrom(path: String): LocalP2PFile = {
        val file = new File(path)
        val (chunkHashes, fileHash) = hashTheFile(file)
        LocalP2PFile(file, file.length().toInt/BYTES_PER_CHUNK, chunkHashes, fileHash)
    }
}
