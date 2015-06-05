package ethanp.file

import java.io.{BufferedInputStream, File, FileInputStream, InputStream}
import java.security.MessageDigest

import ethanp.common.Sha2
import ethanp.firstVersion.Swarm

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
    val fileInfo: FileInfo
}

case class FileInfo(
    filename: String,
    numChunks: Int,
    chunkHashes: Array[Sha2],
    fileHash: Sha2
)

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
    localFileLoc: File // data loc for this file on the local file system
)
extends P2PFile

object LocalP2PFile {

    val BYTES_PER_PIECE = 4
    val PIECES_PER_CHUNK = 4
    val BYTES_PER_CHUNK = BYTES_PER_PIECE * PIECES_PER_CHUNK

    /**
     * hash the entire file without ever holding more than a single chunk in memory
     */
    def hashTheFile(file: File): (Array[Sha2], Sha2) = {

        val readArr          = new Array[Byte](BYTES_PER_CHUNK)
        val fileDigester     = MessageDigest.getInstance("SHA-256")
        def finalFileDigest  = Sha2.digestToBase64(fileDigester.digest())
        val chunkHashes      = mutable.MutableList.empty[Sha2]
        def finalChunkHashes = chunkHashes.toArray

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
                if (filledReadArray) {
                    updateHashes()
                } else if (doneReading) {
                    updateWithArr(readArr.take(offset+bytesRead))
                } else {
                    offset += bytesRead
                }
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
            finally {
                fileInStream.close()
            }
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
                file.length().toInt/BYTES_PER_CHUNK,
                chunkHashes,
                fileHash
            ),
            file
        )
    }
}
