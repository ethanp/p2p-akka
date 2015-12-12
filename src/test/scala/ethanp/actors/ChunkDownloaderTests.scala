package ethanp.actors

import java.io.File

import akka.testkit.{TestActorRef, TestProbe}
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
    val testChunkIdx = 0

    val chunkSize = input2TextP2P.fileInfo numBytesInChunk testChunkIdx

    val piecesInChunk = input2TextP2P.fileInfo numPiecesInChunk testChunkIdx

    val invalidPieceData = Array[Byte](12, 32, 42)

    var parent = TestProbe()

    /* Create a ChunkDownloader to test
     *
     * Note that the ChunkDownloader' parent (viz. `self`) is
     * _automatically_ added to `listeners`.
     */
    val chunkDownloaderRef = TestActorRef(
        ChunkDownloader.props(
            p2PFile = outputP2PFile,
            chunkIdx = testChunkIdx,
            peerRef = self
        ),
        parent.ref,
        "ChunkDownloader-UnderTest"
    )

    val chunkDownloaderPtr: ChunkDownloader = chunkDownloaderRef.underlyingActor

    "newly spawned chunk downloader" should {
        "request chunk from specified peer" in {
            expectSoon {
                ChunkRequest(
                    infoAbbrev = outputP2PFile.fileInfo.abbreviation,
                    chunkIdx = testChunkIdx
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
        "starting up" should {
            "have no pieces" in {
                noPiecesShouldHaveBeenReceived()
            }
        }

        "received first piece" should {
            "mark only first piece as received" in {
                val bytes = input2TextP2P.readBytesForPiece(testChunkIdx, 0).get
                chunkDownloaderRef ! Piece(0, bytes)
                firstPieceShouldHaveBeenReceived()
            }
        }

        "received all pieces" should {
            "mark all pieces off" in {
                /* send the rest of the pieces over */
                for (i <- 1 until piecesInChunk) {
                    val bytes = input2TextP2P.readBytesForPiece(testChunkIdx, i).get
                    chunkDownloaderRef ! Piece(i, bytes)
                }
                allPiecesShouldHaveBeenReceived()
            }
            /* NOTE: One CANNOT just run this 'unit' test.
             * You must run the entire class for this test to pass.
             */
            "notify listeners of download success" in {
                // SOMEDAY still not sure this piece of the protocol will ever come in handy
                // (because, the Client ALREADY KNOWS the chunk size from `FileInfo` object)
                chunkDownloaderRef ! ChunkSuccess

                parent.expectMsgClass(classOf[ChunkCompleteData])
            }
        }
    }
}

class ChunkDLInvalidDataTest extends BaseChunkDLTester {
    "a ChunkDownloader" when {
        "receiving invalid data" should {

            /* send client the fake data */
            for (i ← 0 to 2) chunkDownloaderRef ! Piece(i, invalidPieceData)

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
                parent expectMsg {
                    ChunkDLFailed(
                        chunkIdx = testChunkIdx,
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

            /* send client the fake data */
            chunkDownloaderRef ! Piece(0, invalidPieceData)

            "notify parent of failure and peer" in {
                // not being in an "in" block made this test fail?!
                parent.within(chunkDownloaderPtr.RECEIVE_TIMEOUT + 2.seconds) {
                    parent.expectMsg(ChunkDLFailed(0, self, TransferTimeout))
                }
                chunkDownloaderPtr.piecesRcvd shouldEqual pieceArrayWithFirstPieceTrue
            }
            "not write the chunk to disk" in {
                output1Txt shouldNot exist
            }
        }
    }
}
