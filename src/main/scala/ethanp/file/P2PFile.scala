package ethanp.file

import java.io._
import java.security.MessageDigest

import ethanp.common.Sha2
import ethanp.file.LocalP2PFile._
import ethanp.firstVersion.Swarm

import scala.collection.mutable
import scala.util.{Success, Failure, Try}

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
    val fileInfo: FileInfo
}

case class FileInfo(
    filename: String,
    chunkHashes: Vector[Sha2],
    fileHash: Sha2,
    fileLength: Int
) {
    val lastChunkIdx = (fileLength.toDouble / BYTES_PER_CHUNK).ceil.toInt - 1
    val lastChunkStartLoc = lastChunkIdx * BYTES_PER_CHUNK
    val numChunks = chunkHashes.length

    def numBytesInChunk(chunkIdx: Int): Int =
        if (chunkIdx < lastChunkIdx) BYTES_PER_CHUNK
        else fileLength - lastChunkStartLoc

    def numPiecesInChunk(chunkIdx: Int): Int =
        if (chunkIdx < lastChunkIdx) PIECES_PER_CHUNK
        else (numBytesInChunk(chunkIdx).toDouble / BYTES_PER_PIECE).ceil.toInt
}

/**
 * sent through akka by the Tracker, when a file is requested
 */
case class FileToDownload(
    fileInfo: FileInfo,
    swarm : Swarm
)
extends P2PFile

case class LocalP2PFile(
    fileInfo: FileInfo,
    file: File // data loc for this file on the local file system
)
extends P2PFile
{
    def getPiece(chunkIdx: Int, pieceIdx: Int): Try[Array[Byte]] = {
        val in = new RandomAccessFile(file, "r")
        val startLoc = chunkIdx * BYTES_PER_CHUNK + pieceIdx * BYTES_PER_PIECE
        val pieceLen = Math.min(BYTES_PER_PIECE, fileInfo.fileLength - startLoc)
        try {
            in.seek(startLoc)
            val arr = new Array[Byte](pieceLen)
            val read = in.read(arr)
            // we assume the entire piece can be read in one go...
            val readComplete = read == -1 || read == pieceLen
            if (readComplete) Success(arr)
            else Failure(new ReadFailedException)
        }
        catch { case e: Throwable ⇒ Failure(e) }
        finally in.close()
    }
}

object LocalP2PFile {
    class ReadFailedException extends Exception
    val BYTES_PER_PIECE = 4
    val PIECES_PER_CHUNK = 4
    val BYTES_PER_CHUNK = BYTES_PER_PIECE * PIECES_PER_CHUNK

    /**
     * hash the entire file without ever holding more than a single chunk in memory
     */
    def hashTheFile(file: File): (Vector[Sha2], Sha2) = {

        val readArr          = new Array[Byte](BYTES_PER_CHUNK)
        val fileDigester     = MessageDigest.getInstance("SHA-256")
        def finalFileDigest  = Sha2.digestToBase64(fileDigester.digest())
        val chunkHashes      = mutable.MutableList.empty[Sha2]
        def finalChunkHashes = chunkHashes.toVector

        val len         = file.length()
        var bytesRead   = 1
        var offset      = 0
        var totalRead   = 0

        def doneReading = totalRead >= len
        def filledReadArray = offset + bytesRead == readArr.length

        def updateHashes(): Unit = {
            updateWithArr(readArr)
            offset = 0
        }

        def updateWithArr(arr: Array[Byte]): Unit = {
            fileDigester.update(arr)
            chunkHashes += Sha2.hashOf(arr)
        }

        def readFile(fis: InputStream) {
            while (!doneReading) {
                bytesRead = fis.read(readArr, offset, readArr.length - offset)
                totalRead += bytesRead
                if (filledReadArray) updateHashes()
                else if (doneReading) updateWithArr(readArr.take(offset+bytesRead))
                else offset += bytesRead
            }
        }

        def readCarefully() {
            val fileInStream = new BufferedInputStream(new FileInputStream(file))
            try readFile(fileInStream)
            catch {
                case e: Exception ⇒
                    e.printStackTrace()
                    System.exit(5)
            }
            finally fileInStream.close()
        }

        readCarefully()
        finalChunkHashes → finalFileDigest
    }

    def loadFile(name: String, path: String): LocalP2PFile = {
        val file = new File(path)
        val (chunkHashes, fileHash) = hashTheFile(file)
        println(s"file hash: $fileHash")
        println("chunk hashes:\n----------")
        chunkHashes.foreach(println)
        LocalP2PFile(
            FileInfo(
                name,
                chunkHashes,
                fileHash,
                file.length().toInt
            ),
            file
        )
    }
}
