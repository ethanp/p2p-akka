package ethanp.actors

import java.io.File

import akka.actor.{ActorRef, Props}
import akka.testkit.TestActorRef
import ethanp.actors.BaseTester.ForwardingActor
import ethanp.backend.client._
import ethanp.backend.{FileDownloader, Leecher}
import ethanp.file.FileToDownload

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

/**
  * PREFACE
  * - Create { 3 liveSeeders, 2 deadSeeders, 2 [sic] liveLeechers, 3 deadLeechers }
  * - Pass them to the FileDownloader (constructor parameter)
  *
  * NARRATIVE
  * - The FileDownloader asks for their `Avblty`s
  * + In that way, it finds out the categories of Peers created above
  * - It downloads the chunks, and sends its listeners a DownloadSuccess
  */
class FileDownloaderTestLiveAndDeadSeedersAndLeechers extends FileDownloaderTest {
    "there are live & dead seeders & leechers" when {
        val peers = (1 to 10).map(i => system.actorOf(Props(classOf[ForwardingActor], self), "fwd-actor-" + i)).toSet
        val (seeders, leechers) = splitAtIndex(peers, 5)
        val (liveSeeders, deadSeeders) = splitAtIndex(seeders, 3)
        val (liveLeechers, deadLeechers) = splitAtIndex(leechers, 2)
        val livePeers = liveSeeders ++ liveLeechers
        val deadPeers = deadSeeders ++ deadLeechers

        val fileToDownload = FileToDownload(fileInfo, seeders, leechers)
        val fdActorRef = TestActorRef(Props(classOf[FileDownloader], fileToDownload, downloadDir), self, "FileDownloaderUnderTest")
        val fdInstance: FileDownloader = fdActorRef.underlyingActor

        "first starting up" should {
            "Ping all peers to check which are alive" in {
                quickly {
                    expectNOf(peers.size, GetAvblty(fileInfo.abbreviation))
                }
            }
        }

        /* the seeders are supposed to respond to the above `GetAvblty` with a `Seeding` msg */
        liveSeeders foreach (fdActorRef.tell(Seeding, _))

        // test file has "3" chunks (won't work for a file with less than 3)
        /* inform it of avbl of leechers */


        def assignAvailabilities(f: (mutable.BitSet, ActorRef) => Any): Any = {
            var avbl = new mutable.BitSet(fileInfo.numChunks)
            for ((leecher, idx) ← liveLeechers.zipWithIndex) {
                avbl += idx
                f(avbl, leecher)
            }
        }

        assignAvailabilities { (avbl, leecher) =>
            fdActorRef.tell(Leeching(avbl.toImmutable), leecher)
        }

        "getting chunk availabilities" should {
            "believe seeders are seeders" in {
                fdInstance.liveSeederRefs shouldEqual liveSeeders
            }
            "know avbl of leechers" in {
                assignAvailabilities { (avbl, leecher) =>
                    fdInstance.liveLeechers should contain(Leecher(avbl, leecher))
                }
            }
            "leave aside peers who don't respond" in {
                fdInstance.peersWhoHaventResponded.size shouldEqual deadPeers.size
            }

            "spawn the right number of chunk downloaders" in {
                val numConcurrentDLs = Seq(fdInstance.maxConcurrentChunks, livePeers.size).min
                fdInstance.chunkDownloaders should have size numConcurrentDLs
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
                        This is what the `ChunkDownloader` WOULD be doing,
                        but I'M sending the `ChunkComplete`s now
                        instead of having a `ChunkDownloader`.

                        TODO is that true? If it is it represents shared mutable state
                        between the `FileDownloader` and the `ChunkDownloader`

                        TODO This should be investigated
                    */
                    fdInstance.p2PFile.unavailableChunkIndexes.remove(i)
                    fdActorRef ! ChunkComplete(i)
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
                i: Int => system.actorOf(Props(classOf[ForwardingActor], self), "part2-" + i)
            }

            val ftd = FileToDownload(fileInfo, Set.empty, leechers)
            val fdActorRef = TestActorRef(Props(classOf[FileDownloader], ftd, downloadDir), self, "fdl-2")
            val fdInstance: FileDownloader = fdActorRef.underlyingActor

            "first starting up" should {
                "check which peers are alive" in {
                    quickly(expectNOf(leechers.size, GetAvblty(fileInfo.abbreviation)))
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

                        fdActorRef.tell(Leeching(immutableAvbl), leecher)
                        expectedLeechers ::= Leecher(mutableAvbl, leecher)
                    }

                    // trust, but verify
                    fdInstance.liveLeechers shouldEqual expectedLeechers.toSet
                }
                "spawn the first three chunk downloaders" in {
                    val numConcurrentDLs = Seq(fdInstance.maxConcurrentChunks, leechers.size).min
                    fdInstance.chunkDownloaders should have size numConcurrentDLs
                    quickly((1 to numConcurrentDLs) foreach { _ =>
                        expectMsgClass(classOf[ChunkRequest])
                    })
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
                i: Int => system.actorOf(Props(classOf[ForwardingActor], self), "part2-" + i)
            }

            val ftd = FileToDownload(fileInfo, seeders = Set.empty, leechers = leechers)
            val fdActorRef = TestActorRef(Props(classOf[FileDownloader], ftd, downloadDir), self, "fdl-3")
            val fdInstance: FileDownloader = fdActorRef.underlyingActor

            "first starting up" should {
                "check status of all potential peers" in {
                    quickly(expectNOf(leechers.size, GetAvblty(fileInfo.abbreviation)))
                }
            }

//            fdInstance.retryDownloadInterval = 2 seconds

            val numChunkDownloaders = Seq(availableChunksIndexes.size, fdInstance.maxConcurrentChunks).min
            "getting chunk availabilities" should {
                "know avbl of leechers" in {
                    // test file has "3" chunks, though test doesn't rely on that

                    /* each Leecher has only the chunk corresponding to her idx */
                    for ((leecher, leecherIdx) ← leechers.zipWithIndex) {
                        val avbl = BitSet(fileInfo.numChunks) + leecherIdx
                        fdActorRef.tell(Leeching(avbl), leecher)
                    }

                    // verify (note: we're not *waiting* for the message to travel)
                    for ((leecher, idx) ← leechers.zipWithIndex) {
                        val avbl = new mutable.BitSet(fileInfo.numChunks) + idx
                        fdInstance.liveLeechers should contain(Leecher(avbl, leecher))
                    }
                    fdInstance.availableChunks shouldEqual BitSet(availableChunksIndexes: _*)
                }
                "spawn just the two chunk downloaders" in {
                    fdInstance.chunkDownloaders should have size numChunkDownloaders
                    quickly((0 until numChunkDownloaders) foreach { _ =>
                        expectMsgType[ChunkRequest]
                    })
                }
            }
            "continuing download" should {
                // we get everything we expect to receive from peers
                availableChunksIndexes foreach { chunkIdx =>
                    fdActorRef ! ChunkComplete(chunkIdx)
                }
                "send the rest of the ChunkRequests" in {
                    numChunkDownloaders to availableChunksIndexes.last foreach { _ =>
                        within(200 milliseconds)(expectMsgType[ChunkRequest])
                    }
                }

                // TODO eventually retry connecting with everyone in swarm
                "eventually retry connecting with everyone in swarm" ignore {
                    within(fdInstance.retryDownloadInterval - 1.second)(expectNoMsg())
                    within(2 seconds)(expectMsg(TransferTimeout))
                    assert(true) // bleh.
                }
            }
        }
    }
}

/* TODO A ChunkDownloader TimesOut on its Peer, but there are others that still work.
 *      At that point, we should remove that Peer from the livePeers list or whatever
 *      If there are other peers, we should continue going
 *      Otw we should `expectMsg(NoPeersAvailable)` or something like that
 *
 * OK SO: TODO I've been doing this (slightly) wrong
 *
 *  - I'm mixing the FileDownloader up with the ChunkDownloader.
 *    The problem is that the ChunkDownloader here is timing-out because it's not getting any Pieces.
 *    But I'm not even trying trying to test the ChunkDownloader in here, I'm testing the FileDownloader.
 *    So I need to have the FileDownloader spawn only "fake ChunkDownloaders" and then the code
 *    below should work correctly.
 *    It's on the right track though....
 *
 *  - And then I SHOULD go test the ChunkDownloader's capability to timeout properly as well.
 *    Because now it's clear that when I tested the timeout above, I was actually not really
 *    doing it right.
 */
class PeerTimeoutWithBackups extends FileDownloaderTest {

    "a FileDownloader" when {
        "there are two seeders" when {

            val survivor = system.actorOf(Props(classOf[ForwardingActor], self), "fwd-actor-survivor")
            val dier = system.actorOf(Props(classOf[ForwardingActor], self), "fwd-actor-dier")
            val seeders = Set(survivor, dier)
            val fileToDownload = FileToDownload(fileInfo, seeders, leechers = Set.empty)
            val fdActorRef = TestActorRef(Props(classOf[FileDownloader], fileToDownload, downloadDir), self, "FileDownloaderUnderTest")
            val fdInstance: FileDownloader = fdActorRef.underlyingActor
            fdInstance.retryDownloadInterval = 2 seconds // speed things up for the testing purposes

            "one dies part-way through the transfer" should {
                "do the usual (as tested above)" in {

                    quickly(expectNOf(seeders.size, GetAvblty(fileInfo.abbreviation)))
                    seeders.foreach(seeder => fdActorRef.tell(Seeding, seeder))
                    val numConcurrentDLs = Set(fdInstance.maxConcurrentChunks, seeders.size).min
                    quickly {
                        // we can't count on any particular ordering of requests here
                        val possibilities = List(
                            ChunkRequest(fileInfo.abbreviation, 0),
                            ChunkRequest(fileInfo.abbreviation, 1)
                        )
                        expectMsgAnyOf(possibilities: _*)
                        expectMsgAnyOf(possibilities: _*)
                    }
                }

                "only receive a response from one seeder" in {

                    fdActorRef ! ChunkComplete(0)
                    quickly {
                        val possibilities = List(
                            ChunkRequest(fileInfo.abbreviation, 2),
                            ChunkRequest(fileInfo.abbreviation, 3)
                        )
                        expectMsgAnyOf(possibilities: _*)
                    }
                }

                "timeout on the other seeder" in {

                    within(fdInstance.retryDownloadInterval + 1.second) {
                        val possibilities = seeders.toList map (i =>
                            ChunkDLFailed(0, i, TransferTimeout)
                            )
                        expectMsgAnyOf(possibilities: _*)
                        assert(true)
                    }
                }

                "add it to the associated 'unresponsive peers' list" in {


                }

                "finish the download using only the remaining live peer" in {

                    //                    expectSoon(ChunkRequest(fileInfo.abbreviation, 2))
                }
            }
        }
    }
}
