package ethanp.integration

import java.io.File

import akka.actor.Props
import akka.testkit.TestActorRef
import ethanp.file.FileToDownload
import ethanp.firstVersion._
import ethanp.integration.BaseTester.ForwardingActor

import scala.collection.mutable

/**
 * Ethan Petuchowski
 * 6/14/15
 */
class FileDownloaderTestLiveAndDeadSeedersAndLeechers extends BaseTester {

    /* TODO keep tests at a higher level
     * to make refactoring simpler */

    import inputTextP2P.fileInfo

    "there are live & dead seeders & leechers" when {
        val fwdActors = (1 to 10)
        .map(i => system.actorOf(Props(classOf[ForwardingActor], self), "f-" + i)).toSet
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
                assert(liveSeeders forall fDlPtr.liveSeeders.contains)
            }
            "know avbl of leechers" in {
                // test file has "3" chunks

                // set up
                var unavbl = new mutable.BitSet(fileInfo.numChunks)
                for ((leecher, idx) <- liveLeechers.zipWithIndex) {
                    fDlRef.tell(Leeching((unavbl += idx).toImmutable), leecher)
                }

                // verify
                unavbl = new mutable.BitSet(fileInfo.numChunks)
                for ((leecher, idx) <- liveLeechers.zipWithIndex) {
                    assert(fDlPtr.liveLeechers(leecher) == (unavbl += idx).toImmutable)
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
            "check off chunks as they arrive and inform `parent` of download success" in {
                for (i <- 0 until fileInfo.numChunks) {
                    fDlRef ! ChunkComplete(i)
                }
                quickly {
                    expectMsg(DownloadSuccess(fileInfo.filename))
                }
            }
        }
    }
}
class FileDownloaderTestJustEnoughLeechers extends BaseTester {

    import inputTextP2P.fileInfo

    "there are only other leechers" when {
        "the full file is barely available" when {

            val leechers = (1 to fileInfo.numChunks).map(i => system.actorOf(Props(classOf[ForwardingActor], self), "part2-"+i)).toSet
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
                    // test file has "3" chunks

                    // set up
                    var unavbl = new mutable.BitSet(fileInfo.numChunks)
                    for ((leecher, idx) <- leechers.zipWithIndex) {
                        if (idx > 0) unavbl -= idx-1
                        fDlRef.tell(Leeching((unavbl += idx).toImmutable), leecher)
                    }

                    // verify
                    unavbl = new mutable.BitSet(fileInfo.numChunks)
                    for ((leecher, idx) <- leechers.zipWithIndex) {
                        if (idx > 0) unavbl -= idx-1
                        assert(fDlPtr.liveLeechers(leecher) == (unavbl += idx).toImmutable)
                    }

                }

                // TODO I need to make sure the requests are going to the node that has those chunks

                "spawn the first three chunk downloaders" in {
                    val numConcurrentDLs = Seq(fDlPtr.maxConcurrentChunks, leechers.size).min
                    fDlPtr.chunkDownloaders should have size numConcurrentDLs
                    quickly {
                        for (i ← 1 to fileInfo.numChunks) {
                            expectMsgClass(classOf[ChunkRequest])
                        }
                    }
                }
            }

            "completing download" should {
                "check off chunks as they arrive and inform `parent` of download success" in {
                    for (i <- 0 until fileInfo.numChunks) {
                        fDlRef ! ChunkComplete(i)
                    }
                    quickly {
                        expectMsg(DownloadSuccess(fileInfo.filename))
                    }
                }
            }
        }
    }
}

class FileDownloaderTestNotFullyAvailable extends BaseTester {

    import inputTextP2P.fileInfo

    "there are only other leechers" when {
        "the full file is not available" when {

            val leechers = (1 to fileInfo.numChunks-1).map(i => system.actorOf(Props(classOf[ForwardingActor], self), "part2-"+i)).toSet
            val dlDir = new File("test_downloads")
            dlDir.deleteOnExit()

            val ftd = FileToDownload(fileInfo, Set.empty, leechers)
            val fDlRef = TestActorRef(Props(classOf[FileDownloader], ftd, dlDir), self, "fdl-3")
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
                    // test file has "3" chunks

                    // set up
                    var unavbl = new mutable.BitSet(fileInfo.numChunks)
                    for ((leecher, idx) <- leechers.zipWithIndex) {
                        if (idx > 0) unavbl -= idx-1
                        fDlRef.tell(Leeching((unavbl += idx).toImmutable), leecher)
                    }

                    // verify
                    unavbl = new mutable.BitSet(fileInfo.numChunks)
                    for ((leecher, idx) <- leechers.zipWithIndex) {
                        if (idx > 0) unavbl -= idx-1
                        assert(fDlPtr.liveLeechers(leecher) == (unavbl += idx).toImmutable)
                    }

                }
                "spawn just the two chunk downloaders" in {
                    fDlPtr.chunkDownloaders should have size 2
                    quickly {
                        for (i ← 1 to fileInfo.numChunks-1) {
                            expectMsgClass(classOf[ChunkRequest])
                        }
                    }
                }
            }

            "continuing download" should {
                "check off chunks as they arrive and inform `parent` of download success" in {
                    for (i <- 0 until fileInfo.numChunks-1) {
                        fDlRef ! ChunkComplete(i)
                    }

                    // TODO verify the thing knows what it has and also what it hasn't started
                }

                "eventually timeout" ignore {
                    // TODO expect timeout within 10 seconds or something (not implemented)
                }
            }
        }
    }
}
