package ethanp.actors

import java.io.{File, FileInputStream}

import akka.testkit.TestActorRef
import ethanp.backend.ChunkDownloader
import ethanp.backend.client._
import ethanp.file.LocalP2PFile

import scala.concurrent.duration._

/**
  * Created by Ethan Petuchowski on 7/2/15.
  *
  */
class BaseChunkDLTester extends BaseTester {

    /* this is where the ChunkDownloader will store the Chunks it receives */
    val output1Txt = new File("testfiles/output1.txt")
    output1Txt.delete()
    output1Txt.deleteOnExit()

    /* this file has 3 chunks */
    val outputP2PFile = LocalP2PFile.empty(input2TextP2P.fileInfo, output1Txt)

    /* Our ChunkDownloader-under-test is, responsible for the first chunk of the file.
     * There are 3 PIECES_PER_CHUNK (under current settings).
     */
    val TEST_INDEX = 0

    val chunkSize = input2TextP2P.fileInfo numBytesInChunk TEST_INDEX

    val piecesInChunk = input2TextP2P.fileInfo numPiecesInChunk TEST_INDEX

    /* Create a ChunkDownloader to test
     *
     * Note that the ChunkDownloader' parent (viz. `self`) is
     * _automatically_ added to `listeners`.
     */
    val chunkDownloaderRef = TestActorRef {
        ChunkDownloader.props(
            p2PFile = outputP2PFile,
            chunkIdx = TEST_INDEX,
            peerRef = self
        )
    }

    val chunkDownloaderPtr: ChunkDownloader = chunkDownloaderRef.underlyingActor

    "newly spawned chunk downloader" should {
        "request chunk from specified peer" in {
            expectSoon {
                ChunkRequest(
                    infoAbbrev = outputP2PFile.fileInfo.abbreviation,
                    chunkIdx = TEST_INDEX
                )
            }
        }
    }
    "this test framework" should {
        "have deleted the outfile" in {
            output1Txt shouldNot exist
        }
    }

    def pieceArrayWithFirstPieceTrue = {
        val arr = Array.fill(piecesInChunk)(false)
        arr(0) = true
        arr
    }

    def pieceArrayOf(boolean: Boolean) = Array.fill(piecesInChunk)(boolean)

    def verifyReceived(real: Array[Boolean]) = real shouldEqual chunkDownloaderPtr.piecesRcvd

    def noPiecesShouldHaveBeenReceived() = verifyReceived(pieceArrayOf(false))

    def firstPieceShouldHaveBeenReceived() = verifyReceived(pieceArrayWithFirstPieceTrue)

    def allPiecesShouldHaveBeenReceived() = verifyReceived(pieceArrayOf(true))
}

class ChunkDLValidDataTest extends BaseChunkDLTester {

    "a ChunkDownloader" when {
        "receiving valid pieces" should {
            "start with no pieces" in {
                noPiecesShouldHaveBeenReceived()
            }
            "mark first piece received (and only it) off" in {
                val bytes = input2TextP2P.readBytesForPiece(TEST_INDEX, 0).get
                chunkDownloaderRef ! Piece(0, bytes)
                firstPieceShouldHaveBeenReceived()
            }
            "mark rest of pieces off" in {
                /* send the rest of the pieces over */
                for (i <- 1 until piecesInChunk) {
                    chunkDownloaderRef ! Piece(i, input2TextP2P.readBytesForPiece(TEST_INDEX, i).get)
                }
                allPiecesShouldHaveBeenReceived()
            }
            "notify listeners of download success" in {
                // SOMEDAY still not sure this piece of the protocol will ever come in handy
                // (btw, the Client already KNOWS the chunk size from `FileInfo` object)
                chunkDownloaderRef ! ChunkSuccess
                expectSoon(ChunkComplete(TEST_INDEX))
            }
            "write chunk of CORRECT data to disk" in {
                output1Txt should exist

                /* open written file and real file */
                val realFileReader = new FileInputStream(input2TextP2P.file)
                val fileContentChecker = new FileInputStream(output1Txt)

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
            for (i ← 0 to 2) chunkDownloaderRef ! Piece(i, fakeData)

            "still check off pieces received" in {
                quickly {
                    allPiecesShouldHaveBeenReceived()
                }
            }

            /* tell ChunkDownloader that the transfer is complete*/
            chunkDownloaderRef ! ChunkSuccess

            "not write the chunk to disk" in {
                output1Txt shouldNot exist
            }

            "notify parent of bad peer" in {
                expectSoon {
                    ChunkDLFailed(
                        chunkIdx = TEST_INDEX,
                        peerPath = self,
                        cause = InvalidData
                    )
                }
            }
        }
    }
}

/**
  * The Peer sends the first Piece of data,
  * but never sends the second one,
  * so we must timeout.
  */
class ChunkDLTimeoutTest extends BaseChunkDLTester {
    "a ChunkDownloader" when {
        "timing out on a download" should {
            val fakeData = Array(12.toByte, 32.toByte, 42.toByte)

            /* send client the fake data */
            chunkDownloaderRef ! Piece(0, fakeData)

            "notify parent of failure and peer" in {
                // not being in an "in" block made this test fail?!
                within(chunkDownloaderPtr.RECEIVE_TIMEOUT + 2.seconds) {
                    expectMsg(ChunkDLFailed(0, self, TransferTimeout))
                }
                chunkDownloaderPtr.piecesRcvd shouldEqual pieceArrayWithFirstPieceTrue
            }
            "not write the chunk to disk" in {
                output1Txt shouldNot exist
            }
        }
    }
}
