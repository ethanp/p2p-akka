package ethanp.file

import java.io._

import akka.actor.ActorRef
import ethanp.file.LocalP2PFile._

import scala.collection.mutable
import scala.util.{Failure, Success, Try}
import scala.reflect.io.Path

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

    /** a sha2 hash of the contents of the FileInfo itself */
    lazy val abbreviation: Sha2 = {
        val elemsAsString = filename + (chunkHashes mkString "") + fileLength
        Sha2(elemsAsString.getBytes)
    }
}

/**
 * sent through akka by the Tracker, when a file is requested
 */
case class FileToDownload(
    fileInfo: FileInfo,
    seeders: Set[ActorRef],
    leechers: Set[ActorRef]
)
extends P2PFile
{
    def seed_+(ref: ActorRef) = FileToDownload(fileInfo, seeders + ref, leechers)
    def leech_+(ref: ActorRef) = FileToDownload(fileInfo, seeders, leechers + ref)
    def seed_-(ref: ActorRef) = FileToDownload(fileInfo, seeders - ref, leechers)
    def leech_-(ref: ActorRef) = FileToDownload(fileInfo, seeders, leechers - ref)
}

case class LocalP2PFile(
    fileInfo: FileInfo,
    file: File, // data loc for this file on the local file system

//  TODO this seems like a bad idea.
    unavbl: mutable.BitSet
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
        catch { case e: Throwable => Failure(e) }
        finally in.close()
    }
}

object LocalP2PFile {
    class ReadFailedException extends Exception
    val BYTES_PER_PIECE = 1024
    val PIECES_PER_CHUNK = 3
    val BYTES_PER_CHUNK = BYTES_PER_PIECE * PIECES_PER_CHUNK

    /**
     * Create a vector of the Sha2 hashes of each Chunk of the given file,
     * without ever holding more than a single chunk in memory.
     *
     * @param file the file whose Chunks to hash using Sha2
     * @return a vector of Chunk hashes
     */
    def hashTheFile(file: File): Vector[Sha2] = {
        var chunkHashes: mutable.MutableList[Sha2] = mutable.MutableList.empty
        readFileByChunk(file) (
            useFileData = (buffer, fullPortion, _) => chunkHashes += (Sha2 hashOf (buffer take fullPortion)),
            onComplete = chunkHashes.toVector
        )
    }

    /**
     * Reads the file chunk by chunk and executes the given callback.
     * Returns whatever you want it to.
     *
     * TODO this SHOULD be a `foldFileByChunk` method that deals with an
     *      _immutable_ input object and folds over it, which would in my
     *      case yield the Vector[Sha2]
     *
     * @param useFileData what to do whenever a buffer is filled or the with the last file buffer
     * @param onComplete what to return upon completion (lazy)
     * @return the result of the `onComplete` callback
     */
    def readFileByChunk[T](file: File)(
        useFileData : (Array[Byte], Int, Int) => Unit,
        onComplete  : => T
    ): T = {
        val fileInputStream = new BufferedInputStream(new FileInputStream(file))
        val chunkBuffer     = new Array[Byte](BYTES_PER_CHUNK)
        var bytesRead       = -1
        var offsetInChunk   = 0
        var totalRead       = 0

        while (totalRead < file.length()) {

            // FROM     the InputStream
            // INTO     the `chunkBuffer`
            // STARTING AT offset
            // AT MOST  only the remaining bytes in the Chunk
            // RETURNS  a non-negative integer
            bytesRead = fileInputStream.read(chunkBuffer, offsetInChunk, BYTES_PER_CHUNK - offsetInChunk)

            totalRead += bytesRead
            offsetInChunk += bytesRead

            if (offsetInChunk == chunkBuffer.length || totalRead >= file.length()) {
                useFileData(chunkBuffer, offsetInChunk, totalRead)
                offsetInChunk = 0
            }
        }
        fileInputStream.close()
        onComplete
    }

    def loadFile(path: Path, name: String): LocalP2PFile = loadFile(name, path.toString())


    def loadFile(givenName: String, filePath: String): LocalP2PFile = {
        val file = new File(filePath)
        val chunkHashes: Vector[Sha2] = hashTheFile(file)
        LocalP2PFile(
            FileInfo(
                givenName,
                chunkHashes,
                file.length().toInt
            ),
            file = file,
            unavbl = new mutable.BitSet(chunkHashes.length) // if you loaded the file, you must have all of it
        )
    }

    def empty(fileInfo: FileInfo, file: File) = LocalP2PFile(fileInfo, file, mutable.BitSet(0 until fileInfo.numChunks:_*))
}
