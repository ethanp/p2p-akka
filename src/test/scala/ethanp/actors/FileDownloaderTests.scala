package ethanp.actors

import java.io.File

import akka.actor.Props
import akka.testkit.TestActorRef
import ethanp.actors.BaseTester.ForwardingActor
import ethanp.file.FileToDownload
import ethanp.firstVersion._

import scala.collection.{BitSet, mutable}
import scala.concurrent.duration._
import scala.language.postfixOps

/**
 * Ethan Petuchowski
 * 6/14/15
 */

/** NOTE: run ALL tests using `sbt test` */
class FileDownloaderTestLiveAndDeadSeedersAndLeechers extends BaseTester {

    /* TODO keep tests at a high level
     * to make refactoring simpler */

    import inputTextP2P.fileInfo

    "there are live & dead seeders & leechers" when {
        val fwdActors = (1 to 10).map(i => system.actorOf(Props(classOf[ForwardingActor], self), "f-" + i)).toSet
        val (seeders, leechers) = splitAtIndex(fwdActors, 5)
        //            val ftd = FileToDownload(testTextP2P.fileInfo, seeders, leechers)

        val (liveSeeders, deadSeeders) = splitAtIndex(seeders, 3)
        val (liveLeechers, deadLeechers) = splitAtIndex(leechers, 2)
        val livePeers = liveSeeders ++ liveLeechers
        val deadPeers = deadSeeders ++ deadLeechers
        val dlDir = new File("test_downloads")
        dlDir.deleteOnExit()

        val ftd = FileToDownload(fileInfo, seeders, leechers)
        val fDlRef = TestActorRef(Props(classOf[FileDownloader], ftd, dlDir), self, "fdl")
        val fDlPtr: FileDownloader = fDlRef.underlyingActor

        "first starting up" should {
            "check which peers are alive" in {
                quickly {
                    expectNOf(fwdActors.size, Ping(fileInfo.abbreviation))
                }
            }
        }

        "getting chunk availabilities" should {
            "believe seeders are seeders" in {
                liveSeeders.foreach(fDlRef.tell(Seeding, _))
                assert(fDlPtr.liveSeederRefs forall liveSeeders.contains)
            }
            "know avbl of leechers" in {
                // test file has "3" chunks (test doesn't rely on that)

                /* inform it of avbl of leechers */
                var avbl = new mutable.BitSet(fileInfo.numChunks)
                for ((leecher, idx) ← liveLeechers.zipWithIndex) {
                    fDlRef.tell(Leeching((avbl += idx).toImmutable), leecher)
                }

                /* check if it understood */
                avbl = new mutable.BitSet(fileInfo.numChunks)
                for ((leecher, idx) ← liveLeechers.zipWithIndex) {
                    fDlPtr.liveLeechers should contain (Leecher(avbl += idx, leecher))
                }

            }
            "leave aside peers who don't respond" in {
                fDlPtr.nonResponsiveDownloadees.size shouldEqual deadPeers.size
            }

            "spawn the first three chunk downloaders" in {
                val numConcurrentDLs = Seq(fDlPtr.maxConcurrentChunks, livePeers.size).min
                fDlPtr.chunkDownloaders should have size numConcurrentDLs
                quickly {
                    for (i ← 1 to fileInfo.numChunks) {
                        expectMsgClass(classOf[ChunkRequest])
                    }
                }
            }
        }

        "completing download" should {
            "check off chunks as they arrive in random order, and inform `parent` of download success" in {
                val shuffledIdxs = util.Random.shuffle(Vector(0 until fileInfo.numChunks:_*))
                for (i ← shuffledIdxs) {
                    fDlRef ! ChunkComplete(i)
                }
                expectSoon {
                    DownloadSuccess(fileInfo.filename)
                }
            }
        }
    }
}
class FileDownloaderTestJustEnoughLeechers extends BaseTester {

    import inputTextP2P.fileInfo

    "a FileDownloader" when {
        "the full file is barely available" when {

            val leechers = (1 to fileInfo.numChunks).toSet map {
                i: Int => system.actorOf(Props(classOf[ForwardingActor], self), "part2-"+i)
            }

            val dlDir = new File("test_downloads")
            dlDir.deleteOnExit()

            val ftd = FileToDownload(fileInfo, Set.empty, leechers)
            val fDlRef = TestActorRef(Props(classOf[FileDownloader], ftd, dlDir), self, "fdl-2")
            val fDlPtr: FileDownloader = fDlRef.underlyingActor

            "first starting up" should {
                "check which peers are alive" in {
                    quickly {
                        expectNOf(leechers.size, Ping(fileInfo.abbreviation))
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

class FileDownloaderTestNotFullyAvailable extends BaseTester {

    import inputTextP2P.fileInfo

    "a FileDownloader" when {
        "the full file is not fully available" when {

            val availableChunks = 0 until fileInfo.numChunks - 1

            /* there are not as many Leechers as Chunks to be downloaded */
            val leechers = availableChunks.toSet map {
                i: Int => system.actorOf(Props(classOf[ForwardingActor], self), "part2-"+i)
            }

            val dlDir = new File("test_downloads")

            // Requests that the file or directory denoted by this abstract
            // pathname be deleted when the virtual machine terminates.
            dlDir.deleteOnExit()

            val ftd = FileToDownload(fileInfo, seeders = Set.empty, leechers = leechers)
            val fDlRef = TestActorRef(Props(classOf[FileDownloader], ftd, dlDir), self, "fdl-3")
            val fDlPtr: FileDownloader = fDlRef.underlyingActor

            // speed things up for testing purposes
            fDlPtr.progressTimeout = 2 seconds

            "first starting up" should {
                "check status of all potential peers" in {
                    quickly {
                        expectNOf(leechers.size, Ping(fileInfo.abbreviation))
                    }
                }
            }

            val s = Seq(availableChunks.size, fDlPtr.maxConcurrentChunks).min
            "getting chunk availabilities" should {
                "know avbl of leechers" in {
                    // test file has "3" chunks, though test doesn't rely on that

                    /* each Leecher has only the chunk corresponding to her idx */
                    for ((leecher, idx) ← leechers.zipWithIndex) {
                        val avbl = BitSet(fileInfo.numChunks) + idx
                        fDlRef.tell(Leeching(avbl), leecher)
                    }

                    // verify (note: we're not *waiting* for the message to travel)
                    for ((leecher, idx) ← leechers.zipWithIndex) {
                        val avbl = new mutable.BitSet(fileInfo.numChunks) + idx
                        fDlPtr.liveLeechers should contain (Leecher(avbl, leecher))
                    }
                    fDlPtr.availableChunks shouldEqual BitSet(availableChunks:_*)
                }
                "spawn just the two chunk downloaders" in {
                    fDlPtr.chunkDownloaders should have size s
                    quickly {
                        for (i ← 0 until s) {
                            expectMsgType[ChunkRequest]
                        }
                    }
                }
            }
            "continuing download" should {
                // we get everything we expect to receive from peers
                for (i ← availableChunks) {
                    fDlRef ! ChunkComplete(i)
                }
                "receive the rest of the ChunkRequests" in {
                    for (i ← s to availableChunks.max) {
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
                    assert(true)
                }
            }
        }
    }
}
