package ethanp.integration

import java.io.File

import ethanp.file.{Sha2, FileInfo, LocalP2PFile}

/**
 * Ethan Petuchowski
 * 6/14/15
 */
class P2PFileTests extends BaseTester {
    "A LocalP2PFile" should {
        "look as follows" in {
            testTextP2P should equal (LocalP2PFile(FileInfo(
                filename = testText,
                chunkHashes = Vector(Sha2("fND18YuOoWW8VoyGYs0sIVGXbaneeTGKPXVpgNLd9zQ=")),
                fileLength = 53),
                file = new File(testTextLoc))
            )
        }
    }
}
