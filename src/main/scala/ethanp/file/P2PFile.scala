package ethanp.file

import java.io._

import akka.actor.ActorRef
import ethanp.file.LocalP2PFile._

import scala.collection.mutable
import scala.reflect.io.Path
import scala.util.{Failure, Success, Try}

/**
  * Ethan Petuchowski
  * 6/3/15
  *
  * The goal is that a file never exists fully in memory
  * It is a collection of "chunks"
  * Those "chunks" get transmitted in batches of "pieces"
  * Each "piece" is transmitted as an akka message
  * This is when the progress bar may be updated
  */

/**
  * The P2PFile contains metadata about a file that a peer requires
  * to be able to up/download the file
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
  * This this is how the Tracker responds to a file request.
  * It informs the Client addresses of nodes that may have the file.
  * The FileToDownload instance is IMMUTABLE, but you can do an (inefficient) copy-on-mutate.
  */
case class FileToDownload(
    fileInfo: FileInfo,
    seeders: Set[ActorRef],
    leechers: Set[ActorRef]
)
    extends P2PFile {
    def seed_+(ref: ActorRef) = FileToDownload(fileInfo, seeders + ref, leechers)

    def leech_+(ref: ActorRef) = FileToDownload(fileInfo, seeders, leechers + ref)

    def seed_-(ref: ActorRef) = FileToDownload(fileInfo, seeders - ref, leechers)

    def leech_-(ref: ActorRef) = FileToDownload(fileInfo, seeders, leechers - ref)
}

/** knows
  * the desired name,
  * the file's location on disk,
  * the file's hash vector,
  * the fact that I own the whole file
  */
case class LocalP2PFile(
    fileInfo: FileInfo,
    file: File, // data loc for this file on the local file system

    // SOMEDAY this just seems like an accident waiting to happen.
    unavailableChunkIndexes: mutable.BitSet
)
    extends P2PFile {
    /**
      * Read the specified Piece from the Chunk of the File on-disk
      *
      * @param chunkIdx which Chunk contains the desired piece
      * @param pieceIdx which Piece to read within that Chunk
      * @return either the desired Piece's data, or a `ReadFailedException`
      */
    def getPiece(chunkIdx: Int, pieceIdx: Int): Try[Array[Byte]] = {
        val fileStream = new RandomAccessFile(file, "r")
        val firstByteLoc = chunkIdx * BYTES_PER_CHUNK + pieceIdx * BYTES_PER_PIECE
        val pieceLen = Math.min(BYTES_PER_PIECE, fileInfo.fileLength - firstByteLoc)
        try {
            fileStream.seek(firstByteLoc)
            val arr = new Array[Byte](pieceLen)
            val bytesRead = fileStream.read(arr)

            // we assume the entire piece can be read in one go...
            if (bytesRead == -1 || bytesRead == pieceLen) Success(arr)
            else Failure(new ReadFailedException)
        }
        catch {case e: Throwable => Failure(e)}
        finally fileStream.close()
    }

    def doesntHaveChunk(idx: Int): Boolean = unavailableChunkIndexes contains idx

    def hasDataForChunk(idx: Int): Boolean = !doesntHaveChunk(idx)
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
        readFileByChunk(file)(
            useFileData = (buffer, fullPortion, _) => chunkHashes += (Sha2 hashOf (buffer take fullPortion)),
            onComplete = chunkHashes.toVector
        )
    }

    /**
      * Reads the file chunk by chunk and executes the given callback.
      * Returns whatever you want it to.
      *
      * SOMEDAY this SHOULD be a `foldFileByChunk` method that deals with an
      * _immutable_ input object and folds over it, which would in my
      * case yield the Vector[Sha2]
      *
      * @param useFileData callback whenever a buffer is filled or the with the last file buffer
      * @param onComplete callback whose return-value to return from this method upon completion (lazy)
      * @return the result of the `onComplete` callback
      */
    def readFileByChunk[T](file: File)(
        useFileData: (Array[Byte], Int, Int) => Unit,
        onComplete: => T
    ): T = {
        val fileInputStream = new BufferedInputStream(new FileInputStream(file))
        val chunkBuffer = new Array[Byte](BYTES_PER_CHUNK)
        var bytesRead = -1
        var offsetInChunk = 0
        var totalRead = 0

        while (totalRead < file.length()) {

            // FROM     the InputStream
            // INTO     the `chunkBuffer`
            // START AT first unfilled byte of `chunkBuffer`
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

    /** Create a `LocalP2PFile` with the `givenName` from the file at `filePath`
      *
      * @param givenName The name to be bestowed upon the resulting `LocalP2PFile` object
      * @param filePath The location of the `File` in the local filesystem
      * @return a well-formed `LocalP2PFile`
      */
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
            // if you loaded the file, you must have all of it
            unavailableChunkIndexes = new mutable.BitSet(chunkHashes.length)
        )
    }

    /** Overloading of the above loadFile method
      */
    def loadFile(name: String, path: Path): LocalP2PFile = loadFile(name, path.toString())

    /** Create a LocalP2PFile representation of the given File, where it is known that
      * THIS user doesn't physically own any of the File's data yet.
      */
    def empty(fileInfo: FileInfo, file: File) = LocalP2PFile(
        fileInfo, file, mutable.BitSet(0 until fileInfo.numChunks: _*)
    )
}
