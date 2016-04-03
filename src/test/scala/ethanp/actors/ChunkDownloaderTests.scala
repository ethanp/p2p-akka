package ethanp.actors

import java.io.File

import akka.testkit.{TestActorRef, TestProbe}
import ethanp.backend.ChunkDownloader
import ethanp.backend.client._
import ethanp.file.LocalP2PFile

import scala.concurrent.duration._
import scala.util.Random

/**
  * Created by Ethan Petuchowski on 7/2/15.
  *
  */
class BaseChunkDLTester extends BaseTester {

    /* this is where the ChunkDownloader will store the Chunks it receives */
    val output1Txt = new File("testfiles/output1.txt")
    output1Txt.delete()

    /* this file has 3 chunks */
    val outputP2PFile = LocalP2PFile.empty(input2TextP2P.fileInfo, output1Txt)

    /* Our ChunkDownloader-under-test is responsible for the first chunk of the file.
     * There are 3 PIECES_PER_CHUNK (under current settings).
     */
    val testChunkIdx: Int = 0
    val chunkSize: Int = input2TextP2P.fileInfo numBytesInChunk testChunkIdx
    val piecesInChunk: Int = input2TextP2P.fileInfo numPiecesInChunk testChunkIdx
    val invalidPieceData = {
        val a = new Array[Byte](piecesInChunk)
        Random.nextBytes(a)
        a
    }

    /* This is an actor who will receive messages from the ChunkDownloader.
     * Then we can ask it what messages it received.
     */
    var parent = TestProbe()

    /* Create a ChunkDownloader to test
     *
     * Note that the ChunkDownloader's parent (`self`) is
     * _automatically_ added to `listeners`.
     */
    val chunkDownloaderRef = TestActorRef(
        props = ChunkDownloader.props(
            p2PFile = outputP2PFile,
            chunkIdx = testChunkIdx,
            peerRef = self
        ),
        supervisor = parent.ref,
        name = "ChunkDownloader-UnderTest"
    )

    val chunkDownloaderPtr: ChunkDownloader = chunkDownloaderRef.underlyingActor

    // we get the chunk request "out of the way" in this base-test
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

    def pieceArrayOf(boolean: Boolean): Array[Boolean] = Array.fill(piecesInChunk)(boolean)

    def noPieces: Array[Boolean] = pieceArrayOf(false)

    def allPieces: Array[Boolean] = pieceArrayOf(true)

    def pieceArrayWithFirstPieceTrue: Array[Boolean] = true +: pieceArrayOf(false).tail

    def verifyReceived(real: Array[Boolean]): Unit = chunkDownloaderPtr.piecesRcvd shouldEqual real

    def noPiecesShouldHaveBeenReceived(): Unit = verifyReceived(noPieces)

    def firstPieceShouldHaveBeenReceived(): Unit = verifyReceived(pieceArrayWithFirstPieceTrue)

    def allPiecesShouldHaveBeenReceived(): Unit = verifyReceived(allPieces)
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
                val bytes: Array[Byte] = input2TextP2P.readBytesForPiece(testChunkIdx, pieceIdx = 0).get
                chunkDownloaderRef ! Piece(pieceIdx = 0, data = bytes)
                firstPieceShouldHaveBeenReceived()
            }
        }

        "received all pieces" should {
            "mark all pieces off" in {
                /* send the rest of the pieces over */
                for (i ← 1 until piecesInChunk) {
                    val bytes = input2TextP2P.readBytesForPiece(testChunkIdx, i).get
                    chunkDownloaderRef ! Piece(i, bytes)
                }
                allPiecesShouldHaveBeenReceived()
            }
            /* NOTE: One CANNOT just run this 'unit' test.
             * You must run the entire class for this test to pass.
             */
            "notify listeners of download success" in {
                // SOMEDAY this part of the protocol should be removed
                // because the Client ALREADY KNOWS the chunk size from `FileInfo` object;
                // so the ChunkCompleteData message should be sent as soon as all pieces
                // have been received and validated.
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
                parent expectMsg ChunkDLFailed(
                    chunkIdx = testChunkIdx,
                    peerPath = self,
                    cause = InvalidData
                )
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
                    parent expectMsg ChunkDLFailed(
                        chunkIdx = 0,
                        peerPath = self,
                        cause = TransferTimeout
                    )
                }
                chunkDownloaderPtr.piecesRcvd shouldEqual pieceArrayWithFirstPieceTrue
            }
            "not write the chunk to disk" in {
                output1Txt shouldNot exist
            }
        }
    }
}
