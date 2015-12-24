package ethanp.actors

import java.io.File

import akka.actor.{ActorRef, Props}
import akka.testkit.{TestActorRef, TestProbe}
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

    val input2Info = input2TextP2P.fileInfo
    val downloadDir = new File(s"test_downloads-${this.getClass.getSimpleName}")
    downloadDir.mkdirs()

    /* Requests that the file or directory denoted by this abstract
     * pathname be deleted when the virtual machine terminates.
     *
     * Deletion will be attempted only for normal termination of the
     * virtual machine, as defined by the Java Language Specification.
     *
     * This registration cannot be cancelled.
     */
    downloadDir.deleteOnExit()

    def bytesForChunk(chunkIdx: Int): Array[Byte] = {
        val pieces = (0 until input2Info.numPiecesInChunk(chunkIdx)).toArray
        pieces flatMap {input2TextP2P.readBytesForPiece(chunkIdx, _).get}
    }
}

/**
  * PREFACE
  * - Create { 3 liveSeeders, 2 deadSeeders, 2 [sic] liveLeechers, 3 deadLeechers }
  * - Pass them to the FileDownloader (constructor parameter)
  *
  * Note: it actually doesn't matter to the FileDownloader whether the dead nodes are seeders
  * or leechers, they will all simply end up in the pile of dead peers.
  *
  * NARRATIVE
  * - The FileDownloader asks for their `Avblty`s
  * + In that way, it finds out the categories of Peers created above
  * - It downloads the chunks, writes the data to disk, and sends its listeners a DownloadSuccess
  */
class FileDownloaderTestLiveAndDeadSeedersAndLeechers extends FileDownloaderTest {
    "there are live & dead seeders & leechers" when {
        val peers = (1 to 10).to[Set] map { i =>
            val fwd = Props(classOf[ForwardingActor], self)
            system.actorOf(fwd, "fwd-actor-" + i)
        }
        val (seeders, leechers) = splitAtIndex(peers, 5)
        val (liveSeeders, deadSeeders) = splitAtIndex(seeders, 3)
        val (liveLeechers, deadLeechers) = splitAtIndex(leechers, 2)
        val livePeers = liveSeeders ++ liveLeechers
        val deadPeers = deadSeeders ++ deadLeechers
        val input2FTD = FileToDownload(input2Info, seeders, leechers)
        val parent = TestProbe()
        val fdActorRef = TestActorRef(
            props = FileDownloader.props(input2FTD, downloadDir),
            supervisor = parent.ref,
            name = "FileDownloaderUnderTest"
        )
        val fdPtr: FileDownloader = fdActorRef.underlyingActor
        fdPtr.localFile.deleteOnExit()

        "first starting up" should {
            "Ping all peers to check which are alive" in {
                quickly {
                    expectNOf(peers.size, GetAvblty(input2Info.abbreviation))
                }
            }
        }

        /* the seeders are supposed to respond to the above `GetAvblty` with a `Seeding` msg */
        liveSeeders foreach (fdActorRef.tell(Seeding, _))

        // test file has "3" chunks (won't work for a file with less than 3)
        /* inform it of avbl of leechers */


        def foreachLeecher(f: (mutable.BitSet, ActorRef) => Any): Any = {
            var avbl = new mutable.BitSet(input2Info.numChunks)
            for ((leecher, idx) ← liveLeechers.zipWithIndex) {
                f(avbl += idx, leecher)
            }
        }

        foreachLeecher { (avbl, leecher) =>
            fdActorRef.tell(Leeching(avbl.toImmutable), leecher)
        }

        "getting chunk availabilities" should {
            "believe seeders are seeders" in {
                fdPtr.liveSeeders map (_.actorRef) shouldEqual liveSeeders
            }
            "know avbl of leechers" in {
                foreachLeecher { (avbl, leecher) =>
                    fdPtr.liveLeechers should contain(Leecher(avbl, leecher))
                }
            }
            "leave aside peers who don't respond" in {
                fdPtr.peersWhoHaventResponded shouldEqual deadPeers
            }

            "spawn the right number of chunk downloaders" in {
                val numConcurrentDLs = Seq(fdPtr.maxConcurrentChunks, livePeers.size).min
                fdPtr.chunkDownloaders should have size numConcurrentDLs

                /* These are sent by the spawned ChunkDownloaders to the "peers",
                 * and those peers all happen to be this test. So we need to
                 * explicitly ignore them. There may be a better way to handle this.
                 */
                quickly {
                    for (i ← 1 to input2Info.numChunks) {
                        expectMsgClass(classOf[ChunkRequest])
                    }
                }
            }
        }

        "completing download" should {
            "check off chunks as they arrive in random order" in {
                val shuffledIdxs = util.Random.shuffle(Vector(0 until input2Info.numChunks: _*))
                for (i ← shuffledIdxs) {
                    fdActorRef ! ChunkCompleteData(i, bytesForChunk(i))
                }
                fdPtr.incompleteChunks shouldBe empty
            }
            "write file data to disk matching original file" in {
                val realFileData = io.Source.fromFile(input2TextP2P.file)
                val dlFileData = io.Source.fromFile(fdPtr.localFile)

                fdPtr.localFile should exist
                realFileData.mkString shouldEqual dlFileData.mkString
            }
            "inform `parent` of download success" in {
                parent expectMsg {
                    DownloadSuccess(input2Info.filename)
                }
            }
        }
    }
}

/** First of all, let us be clear: this test is not very useful.
  *
  * In this test, there are no seeders, and each chunk is held by only one leecher.
  * Here, we verify that the right number of chunk requests are sent to the leechers.
  *
  * I'll leave it here in case it comes in handy for something later.
  * Who knows? maybe it will detect a bug someday....
  */
class FileDownloaderTestJustEnoughLeechers extends FileDownloaderTest {
    "a FileDownloader" when {
        "the full file is barely available" when {

            val leechers = (1 to input2Info.numChunks).toSet map {
                i: Int => system.actorOf(Props(classOf[ForwardingActor], self), "part2-" + i)
            }

            val ftd = FileToDownload(input2Info, seeders = Set.empty, leechers = leechers)
            val fdActorRef = TestActorRef(FileDownloader.props(ftd, downloadDir), self, "fdl-2")
            val fdPtr: FileDownloader = fdActorRef.underlyingActor
            fdPtr.localFile.deleteOnExit()

            "first starting up" should {
                "check which peers are alive" in {
                    quickly(expectNOf(leechers.size, GetAvblty(input2Info.abbreviation)))
                }
            }
            "getting chunk availabilities" should {
                "know avbl of leechers" in {
                    /* recall from ScalaDoc string that each leecher has a different chunk */
                    var expectedLeechers = List.empty[Leecher]
                    for ((leecher, idx) ← leechers.zipWithIndex) {
                        val mutableAvbl = new mutable.BitSet(input2Info.numChunks) + idx
                        val immutableAvbl = mutableAvbl.toImmutable

                        fdActorRef.tell(Leeching(immutableAvbl), leecher)
                        expectedLeechers ::= Leecher(mutableAvbl, leecher)
                    }

                    // trust, but verify
                    fdPtr.liveLeechers shouldEqual expectedLeechers.toSet
                }
                "spawn the first three chunk downloaders" in {
                    val numConcurrentDLs = Seq(fdPtr.maxConcurrentChunks, leechers.size).min
                    fdPtr.chunkDownloaders should have size numConcurrentDLs
                    quickly((1 to numConcurrentDLs) foreach { _ =>
                        expectMsgClass(classOf[ChunkRequest])
                    })
                }
            }
        }
    }
}

/** In this test, there are no seeders, and there is a single leecher holding each chunk,
  * except that NO ONE has the LAST chunk.
  *
  * It sends out requests for the first few chunks, and then...
  *
  * The point is that it SHOULD timeout because it doesn't download anything.
  * And this timeout SHOULD trigger a re-pinging of everyone thought to be in
  * the swarm by the tracker. However, I haven't implemented that yet in the
  * code or in this test. So I guess I should implement the test first, and
  * then write the code?
  */
class FileDownloaderTestNotFullyAvailable extends FileDownloaderTest {
    "a FileDownloader" when {
        "the full file is not fully available" when {

            val avblChunkIndices: Range = 0 until input2Info.numChunks - 1

            /* there are not as many Leechers as Chunks to be downloaded */
            val leechers: Set[ActorRef] = avblChunkIndices.toSet map {
                i: Int => system.actorOf(Props(classOf[ForwardingActor], self), "part2-" + i)
            }

            val ftd = FileToDownload(input2Info, seeders = Set.empty, leechers = leechers)
            val fdActorRef = TestActorRef(FileDownloader.props(ftd, downloadDir), self, "fdl-3")
            val fdPtr: FileDownloader = fdActorRef.underlyingActor
            fdPtr.localFile.deleteOnExit()

            /* so we don't have to wait _minutes_ for the test to run */
            fdPtr.retryDownloadInterval = 2 seconds

            /* There are more than enough concurrent-chunk slots to download all available
             * chunks at once. The point is that we still won't spawn that many ChunkDownloaders.
             */
            fdPtr.maxConcurrentChunks = avblChunkIndices.size + 1

            "first starting up" should {
                "check status of all potential peers" in {
                    quickly(expectNOf(leechers.size, GetAvblty(input2Info.abbreviation)))
                }
            }

            "getting chunk availabilities" should {
                "know avbl of leechers" in {
                    // test file has "3" chunks, though test doesn't rely on that

                    /* each Leecher has only the chunk corresponding to her idx */
                    for ((leecher, leecherIdx) ← leechers.zipWithIndex) {
                        val avbl = BitSet(input2Info.numChunks) + leecherIdx
                        fdActorRef.tell(Leeching(avbl), leecher)
                    }

                    // verify (note: we're not *waiting* for the message to travel)
                    for ((leecher, idx) ← leechers.zipWithIndex) {
                        val avbl = new mutable.BitSet(input2Info.numChunks) + idx
                        fdPtr.liveLeechers should contain(Leecher(avbl, leecher))
                    }
                    fdPtr.liveLeechers.size shouldEqual leechers.size
                    fdPtr.availableChunks shouldEqual BitSet(avblChunkIndices: _*)
                }
                "spawn just the two chunk downloaders" in {
                    fdPtr.chunkDownloaders.size shouldEqual leechers.size
                    quickly((0 until leechers.size) foreach { _ =>
                        expectMsgType[ChunkRequest]
                    })
                }
            }
            "continuing download" should {
                /* chunks are received from peers */
                fdPtr.chunkDownloaders.zipWithIndex foreach { case (leecherConn, idx) =>
                    leecherConn ! ChunkCompleteData(idx, bytesForChunk(idx))
                }
                "eventually timeout because the download is not progressing" in {
                    within(fdPtr.retryDownloadInterval - 1.second)(expectNoMsg())
                    within(2 seconds)(expectMsg(TransferTimeout))
                    assert(true) // bleh.
                }

                // TODO test that it will retry connecting with everyone in swarm
                "then retry connecting with everyone in swarm" ignore {
                    quickly(expectNOf(leechers.size, GetAvblty(input2Info.abbreviation)))
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
            val fileToDownload = FileToDownload(input2Info, seeders, leechers = Set.empty)
            val fdActorRef = TestActorRef(Props(classOf[FileDownloader], fileToDownload, downloadDir), self, "FileDownloaderUnderTest")
            val fdInstance: FileDownloader = fdActorRef.underlyingActor
            fdInstance.retryDownloadInterval = 2 seconds // speed things up for the testing purposes

            "one dies part-way through the transfer" should {
                "do the usual (as tested above)" in {

                    quickly(expectNOf(seeders.size, GetAvblty(input2Info.abbreviation)))
                    seeders.foreach(seeder => fdActorRef.tell(Seeding, seeder))
                    val numConcurrentDLs = Set(fdInstance.maxConcurrentChunks, seeders.size).min
                    quickly {
                        // we can't count on any particular ordering of requests here
                        val possibilities = List(
                            ChunkRequest(input2Info.abbreviation, 0),
                            ChunkRequest(input2Info.abbreviation, 1)
                        )
                        expectMsgAnyOf(possibilities: _*)
                        expectMsgAnyOf(possibilities: _*)
                    }
                }

                "only receive a response from one seeder" in {

                    // TODO make this work  fdActorRef ! ChunkCompleteData(0)
                    quickly {
                        val possibilities = List(
                            ChunkRequest(input2Info.abbreviation, 2),
                            ChunkRequest(input2Info.abbreviation, 3)
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
