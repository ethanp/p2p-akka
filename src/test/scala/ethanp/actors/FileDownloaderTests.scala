package ethanp.actors

import java.io.File

import akka.actor.Props
import akka.testkit.TestActorRef
import ethanp.actors.BaseTester.ForwardingActor
import ethanp.backend.{Leecher, FileDownloader}
import ethanp.file.FileToDownload
import ethanp.backend.client._

import scala.collection.{BitSet, mutable}
import scala.concurrent.duration._
import scala.language.postfixOps

/**
 * Ethan Petuchowski
 * 6/14/15
 *
 * NOTE: run ALL tests using `sbt test`
 */

class FileDownloaderTest extends BaseTester {

    /* LowPriorityTodo keep tests at a high level
     * to make refactoring simpler */

    val fileInfo = inputTextP2P.fileInfo
    val downloadDir = new File(s"test_downloads-${this.getClass.getSimpleName}")

    /*
     * Requests that the file or directory denoted by this abstract
     * pathname be deleted when the virtual machine terminates.
     *
     * Deletion will be attempted only for normal termination of the
     * virtual machine, as defined by the Java Language Specification.
     *
     * This registration cannot be cancelled.
     */
    downloadDir.deleteOnExit()

}

class FileDownloaderTestLiveAndDeadSeedersAndLeechers extends FileDownloaderTest {
    "there are live & dead seeders & leechers" when {
        val peers = (1 to 10).map(i => system.actorOf(Props(classOf[ForwardingActor], self), "fwd-actor-" + i)).toSet
        val (seeders, leechers) = splitAtIndex(peers, 5)
        val (liveSeeders, deadSeeders) = splitAtIndex(seeders, 3)
        val (liveLeechers, deadLeechers) = splitAtIndex(leechers, 2)
        val livePeers = liveSeeders ++ liveLeechers
        val deadPeers = deadSeeders ++ deadLeechers

        val fileToDownload = FileToDownload(fileInfo, seeders, leechers)
        val fileDownloaderActorRef = TestActorRef(Props(classOf[FileDownloader], fileToDownload, downloadDir), self, "FileDownloaderUnderTest")
        val fileDownloaderInstance: FileDownloader = fileDownloaderActorRef.underlyingActor

        "first starting up" should {
            "Ping all peers to check which are alive" in {
                quickly {
                    expectNOf(peers.size, GetAvblty(fileInfo.abbreviation))
                }
            }
        }

        "getting chunk availabilities" should {
            "believe seeders are seeders" in {
                liveSeeders.foreach(seeder => fileDownloaderActorRef.tell(Seeding, seeder))
                fileDownloaderInstance.liveSeederRefs shouldEqual liveSeeders
            }
            "know avbl of leechers" in {
                // test file has "3" chunks (test doesn't rely on that)

                /* inform it of avbl of leechers */
                var avbl = new mutable.BitSet(fileInfo.numChunks)
                for ((leecher, idx) ← liveLeechers.zipWithIndex) {
                    fileDownloaderActorRef.tell(Leeching((avbl += idx).toImmutable), leecher)
                }

                /* check if it understood */
                avbl = new mutable.BitSet(fileInfo.numChunks)
                for ((leecher, idx) ← liveLeechers.zipWithIndex) {
                    fileDownloaderInstance.liveLeechers should contain (Leecher(avbl += idx, leecher))
                }

            }
            "leave aside peers who don't respond" in {
                fileDownloaderInstance.peersWhoHaventResponded.size shouldEqual deadPeers.size
            }

            "spawn the right number of chunk downloaders" in {
                val numConcurrentDLs = Seq(fileDownloaderInstance.maxConcurrentChunks, livePeers.size).min
                fileDownloaderInstance.chunkDownloaders should have size numConcurrentDLs
                quickly {
                    for (i ← 1 to fileInfo.numChunks) {
                        expectMsgClass(classOf[ChunkRequest])
                    }
                }
            }
        }

        "completing download" should {
            "check off chunks as they arrive in random order" in {
                val shuffledIdxs = util.Random.shuffle(Vector(0 until fileInfo.numChunks: _*))
                for (i ← shuffledIdxs) {
                    /*
                        this is what the `ChunkDownloader` WOULD be doing,
                        but I'M sending the `ChunkComplete`s now
                        instead of having a `ChunkDownloader`
                    */
                    fileDownloaderInstance.p2PFile.unavailableChunkIndexes.remove(i)
                    fileDownloaderActorRef ! ChunkComplete(i)
                }
            }

            "inform `parent` of download success" in {
                expectSoon {
                    DownloadSuccess(fileInfo.filename)
                }
            }
        }
    }
}
class FileDownloaderTestJustEnoughLeechers extends FileDownloaderTest {
    "a FileDownloader" when {
        "the full file is barely available" when {

            val leechers = (1 to fileInfo.numChunks).toSet map {
                i: Int => system.actorOf(Props(classOf[ForwardingActor], self), "part2-"+i)
            }

            val ftd = FileToDownload(fileInfo, Set.empty, leechers)
            val fDlRef = TestActorRef(Props(classOf[FileDownloader], ftd, downloadDir), self, "fdl-2")
            val fDlPtr: FileDownloader = fDlRef.underlyingActor

            "first starting up" should {
                "check which peers are alive" in {
                    quickly {
                        expectNOf(leechers.size, GetAvblty(fileInfo.abbreviation))
                    }
                }
            }
            "getting chunk availabilities" should {
                "know avbl of leechers" in {
                    /* test file has as many chunks as there are leechers */

                    /* for eg. 3 chunks, we should have the following leechers
                     *
                     *      [1, 0, 0]
                     *      [0, 1, 0]
                     *      [0, 0, 1]
                     *
                     * this is what was meant by "just enough" in this test's title
                     */
                    var expectedLeechers = List.empty[Leecher]
                    for ((leecher, idx) ← leechers.zipWithIndex) {
                        val mutableAvbl = new mutable.BitSet(fileInfo.numChunks) + idx
                        val immutableAvbl = mutableAvbl.toImmutable

                        fDlRef.tell(Leeching(immutableAvbl), leecher)
                        expectedLeechers ::= Leecher(mutableAvbl, leecher)
                    }

                    // trust, but verify
                    fDlPtr.liveLeechers shouldEqual expectedLeechers.toSet
                }
                "spawn the first three chunk downloaders" in {
                    val numConcurrentDLs = Seq(fDlPtr.maxConcurrentChunks, leechers.size).min
                    fDlPtr.chunkDownloaders should have size numConcurrentDLs
                    quickly {
                        for (i ← 1 to numConcurrentDLs) {
                            expectMsgClass(classOf[ChunkRequest])
                        }
                    }
                }
            }
        }
    }
}

class FileDownloaderTestNotFullyAvailable extends FileDownloaderTest {
    "a FileDownloader" when {
        "the full file is not fully available" when {

            val availableChunksIndexes = 0 until fileInfo.numChunks - 1

            /* there are not as many Leechers as Chunks to be downloaded */
            val leechers = availableChunksIndexes.toSet map {
                i: Int => system.actorOf(Props(classOf[ForwardingActor], self), "part2-"+i)
            }

            val ftd = FileToDownload(fileInfo, seeders = Set.empty, leechers = leechers)
            val fDlRef = TestActorRef(Props(classOf[FileDownloader], ftd, downloadDir), self, "fdl-3")
            val fDlPtr: FileDownloader = fDlRef.underlyingActor

            // speed things up for testing purposes
            fDlPtr.progressTimeout = 2 seconds

            "first starting up" should {
                "check status of all potential peers" in {
                    quickly {
                        expectNOf(leechers.size, GetAvblty(fileInfo.abbreviation))
                    }
                }
            }

            val numChunkDownloaders = Seq(availableChunksIndexes.size, fDlPtr.maxConcurrentChunks).min
            "getting chunk availabilities" should {
                "know avbl of leechers" in {
                    // test file has "3" chunks, though test doesn't rely on that

                    /* each Leecher has only the chunk corresponding to her idx */
                    for ((leecher, leecherIdx) ← leechers.zipWithIndex) {
                        val avbl = BitSet(fileInfo.numChunks) + leecherIdx
                        fDlRef.tell(Leeching(avbl), leecher)
                    }

                    // verify (note: we're not *waiting* for the message to travel)
                    for ((leecher, idx) ← leechers.zipWithIndex) {
                        val avbl = new mutable.BitSet(fileInfo.numChunks) + idx
                        fDlPtr.liveLeechers should contain (Leecher(avbl, leecher))
                    }
                    fDlPtr.availableChunks shouldEqual BitSet(availableChunksIndexes:_*)
                }
                "spawn just the two chunk downloaders" in {
                    fDlPtr.chunkDownloaders should have size numChunkDownloaders
                    quickly {
                        for (i ← 0 until numChunkDownloaders) {
                            expectMsgType[ChunkRequest]
                        }
                    }
                }
            }
            "continuing download" should {
                // we get everything we expect to receive from peers
                for (chunkIdx ← availableChunksIndexes) {
                    fDlRef ! ChunkComplete(chunkIdx)
                }
                "receive the rest of the ChunkRequests" in {
                    for (remainingChunkIndex ← numChunkDownloaders to availableChunksIndexes.last) {
                        within(200 milliseconds) {
                            expectMsgType[ChunkRequest]
                        }
                    }
                }
                "eventually receive 'TransferTimeout' status from FDLr" in {
                    within(fDlPtr.progressTimeout - 1.second) {
                        expectNoMsg()
                    }
                    within(2 seconds) {
                        expectMsg(TransferTimeout)
                    }
                    assert(true) // bleh.
                }
            }
        }
    }
}

/* TODO A ChunkDownloader TimesOut on its Peer.
 *      At that point, we should remove that Peer from the livePeers list or whatever
 *      If there are other peers, we should continue going
 *      Otw we should `expectMsg(NoPeersAvailable)` or something like that
 */
class PeerTimeout extends FileDownloaderTest {

}
