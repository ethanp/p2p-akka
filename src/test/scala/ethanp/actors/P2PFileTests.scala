package ethanp.actors

import java.io.File

import ethanp.file.{FileInfo, LocalP2PFile, Sha2}

import scala.collection.mutable

/**
  * Ethan Petuchowski
  * 6/14/15
  */
class P2PFileTests extends BaseTester {

    import LocalP2PFile._

    "A LocalP2PFile" should {
        "look as follows" in {
            val file = new File(testTextLoc)
            val numChunks = (file.length().toDouble / BYTES_PER_CHUNK).ceil.toInt
            testTextP2P should equal(LocalP2PFile(FileInfo(
                filename = testText,
                chunkHashes = Vector(Sha2("fND18YuOoWW8VoyGYs0sIVGXbaneeTGKPXVpgNLd9zQ=")),
                fileLength = 53),
                file = file,
                unavailableChunkIndexes = new mutable.BitSet(numChunks))
            )
        }
    }
}
