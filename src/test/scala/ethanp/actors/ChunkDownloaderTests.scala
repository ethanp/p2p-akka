package ethanp.actors

import java.io.{File, FileInputStream}

import akka.actor.Props
import akka.testkit.TestActorRef
import ethanp.file.LocalP2PFile
import ethanp.firstVersion._

import scala.concurrent.duration._

/**
 * Created by Ethan Petuchowski on 7/2/15.
 *
 */
class BaseChunkDLTester extends BaseTester {

    /* this is where the ChunkDownloader will store the Chunks it receives */
    val localOutFile = new File("testfiles/output1.txt")
    localOutFile.delete()
    localOutFile.deleteOnExit()

    /* this file has 3 chunks */
    val p2pF = LocalP2PFile.empty(inputTextP2P.fileInfo, localOutFile)

    /* our ChunkDownloader-under-test is only responsible for the first chunk
     * there are 3 pieces in this chunk
     */
    val chunkIdx = 0

    val chunkSize = inputTextP2P.fileInfo numBytesInChunk chunkIdx

    /* create a ChunkDownloader who shall
     * request chunkIdx = 0
     * of file = p2pF
     * from peer = self (the test script)
     */
    val cDlRef = TestActorRef(Props(classOf[ChunkDownloader], p2pF, chunkIdx, self))
    val cDlPtr: ChunkDownloader = cDlRef.underlyingActor
    "newly spawned chunk downloader should request chunk from specified peer" in {
        quickly(expectMsgClass(classOf[ChunkRequest]))
    }
    cDlRef ! AddMeAsListener
}

class ChunkDLValidDataTest extends BaseChunkDLTester {
    "this test" should {
        "not already have data in the outfile" in {
            localOutFile shouldNot exist
        }
    }
    "a ChunkDownloader" when {
        "receiving valid pieces" should {
            "have the right receiver buffer" in {
                cDlPtr.piecesRcvd shouldEqual Array(false, false, false)
            }
            "mark first piece received (and only it) off" in {
                val bytes = inputTextP2P.getPiece(chunkIdx, 0).get
                cDlRef ! Piece(bytes, 0)
                quickly {
                    cDlPtr.piecesRcvd shouldEqual Array(true, false, false)
                }
            }
            "mark rest of pieces off" in {
                /* send the rest of the pieces over */
                for (i <- 1 until cDlPtr.piecesRcvd.length)
                    cDlRef ! Piece(inputTextP2P.getPiece(chunkIdx, i).get, i)
                quickly {
                    cDlPtr.piecesRcvd shouldEqual Array(true, true, true)
                }
            }
            "notify listeners of download success" in {
                // still not sure this piece of the protocol will ever come in handy
                // (btw, the Client already KNOWS the chunk size from `FileInfo` object)
                cDlRef ! ChunkSuccess
                expectSoon(ChunkComplete(chunkIdx))
            }
            "write chunk of CORRECT data to disk" in {
                localOutFile should exist

                /* open written file and real file */
                val realFileReader = new FileInputStream(inputTextP2P.file)
                val fileContentChecker = new FileInputStream(localOutFile)

                val realData = new Array[Byte](chunkSize)
                val writtenData = new Array[Byte](chunkSize)

                /* read the contents */
                fileContentChecker read writtenData
                realFileReader read realData

                /* ensure equality */
                writtenData shouldEqual realData
            }
        }
    }
}
class ChunkDLInvalidDataTest extends BaseChunkDLTester {
    "a ChunkDownloader" when {
        "receiving invalid data" should {
            val fakeData = Array[Byte](12, 32, 42)

            /* send client the fake data */
            for (i ← 0 to 2) cDlRef ! Piece(fakeData, i)

            "still check off pieces received" in {
                quickly {
                    cDlPtr.piecesRcvd shouldEqual Array(true, true, true)
                }
            }

            /* tell ChunkDownloader that the transfer is complete*/
            cDlRef ! ChunkSuccess

            "not write the chunk to disk" in {
                localOutFile shouldNot exist
            }
            "notify parent of bad peer" in {
                expectSoon {
                    ChunkDLFailed(
                        chunkIdx = chunkIdx,
                        peerPath = self,
                        cause = InvalidData
                    )
                }
            }
        }
    }
}
class ChunkDLTimeoutTest extends BaseChunkDLTester {
    "a ChunkDownloader" when {
        "timing out on a download" should {
            val fakeData = Array(12.toByte, 32.toByte, 42.toByte)

            /* send client the fake data */
            cDlRef ! Piece(fakeData, 0)

            "notify parent of failure and peer" in {
                // not being in an "in" block made this test fail?!
                within(cDlPtr.receiveTimeout + 2.seconds) {
                    expectMsg(ChunkDLFailed(0, self, TransferTimeout))
                }
                cDlPtr.piecesRcvd shouldEqual Array(true, false, false)
            }
            "not write the chunk to disk" in {
                localOutFile shouldNot exist
            }
        }
    }
}
