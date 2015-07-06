package ethanp.actors

import java.io.File

import akka.actor.Props
import akka.testkit.TestActorRef
import ethanp.file.LocalP2PFile
import ethanp.firstVersion.{ChunkDownloader, Piece}


/**
 * Created by Ethan Petuchowski on 7/2/15.
 *
 */
class ChunkDownloaderTests extends BaseTester {

    val f = new File("testfiles/output1.txt")
    val p2pF = LocalP2PFile.empty(inputTextP2P.fileInfo, f)
    val chunkIdx = 0

    val cDlRef = TestActorRef(Props(classOf[ChunkDownloader], p2pF, chunkIdx, self))
    val cDlPtr: ChunkDownloader = cDlRef.underlyingActor

    "check off piece of data" in {
        val bytes = inputTextP2P.getPiece(chunkIdx, 0).get
        cDlRef ! Piece(bytes, 0)
        quickly {
            cDlPtr piecesRcvd 0 shouldBe true
            cDlPtr piecesRcvd 1 shouldBe false
        }
    }

    "write chunk of data" in {
        // TODO
    }
}
