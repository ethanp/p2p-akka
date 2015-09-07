package ethanp.endToEnd

import akka.actor.{ActorRef, Props}
import akka.testkit.TestActorRef
import akka.contrib.throttle.Throttler._
import ethanp.actors.BaseTester
import ethanp.backend.Client
import ethanp.file.{FileToDownload, LocalP2PFile}
import ethanp.backend.client._
import org.scalatest.Suites

import scala.collection.immutable.IndexedSeq
import scala.collection.mutable
import scala.concurrent.duration._
import scala.io.Source._
import scala.language.postfixOps
import scala.reflect.io.Path

/**
 * Created by Ethan Petuchowski on 7/6/15.
 *
 */
class FullDLTests extends Suites(
    new SingleDL,
    new SingleClientMultiDL,
    new MultiClientMultiDL
)

class DLTests extends BaseTester {
    case class Loc(from: Path, to: Path)

    def filesEqual(loc: Loc): Boolean = filesEqual(loc.from, loc.to)
    def filesEqual(p1: Path, p2: Path): Boolean = filesEqual(p1.toString(), p2.toString())
    def filesEqual(path1: String, path2: String): Boolean =
        fromFile(path1).mkString == fromFile(path2).mkString

    // this var "state" is necessary so that e.g. makeClients can be called
    // multiple times and not create multiple clients with the same "name"
    var actorCtr = 0
    def makeActors(num: Int, props: Props, name: String): IndexedSeq[ActorRef] =
        (1 to num).map { i =>
            actorCtr += 1
            TestActorRef(props, s"$name-$actorCtr")
        }

    def makeFastClients(num: Int) = {
        val clients = makeActors(num, Client.props, "client").map(_.asInstanceOf[TestActorRef[Client]])
        clients foreach (_ ! SetUploadLimit(20 msgsPerSecond))
        clients
    }
    def makeTrackers(num: Int) = makeActors(num, Props[Tracker], "tracker").map(_.asInstanceOf[TestActorRef[Tracker]])
    val (fromDir, toDir): (Path, Path) = ("testfiles", "downloads")
    def fromTo(filename: String) = Loc(fromDir/filename, toDir/filename)
    val testfileNames: IndexedSeq[String] = (2 to 3).map(i => s"input$i.txt") :+ "Test1.txt"
}

class SingleDL extends DLTests {

    "A downloaded file" should {
        "have the same contents" in {
            val clients = makeFastClients(3)
            val filename = "Test1.txt"
            val Loc(from, to) = fromTo(filename)
            val info = LocalP2PFile.loadFile(filename, from.toFile).fileInfo
            to.toFile.delete()

            /* 'tail' clients own the file */
            clients.tail foreach (_.underlyingActor.loadFile(from, filename))
            Thread sleep 100 // let them load & hash the file

            /* listen for e.g. `DownloadSuccess` from client */
            clients.head.underlyingActor.listeners += self

            /* remaining 'head' client downloads file */
            clients.head ! FileToDownload(info, clients.tail.toSet, Set.empty)

            /* see if it worked */
            expectSoon(DownloadSuccess(filename))

            /* verify that downloaded file is actually correct */
            assert(filesEqual(from, to))
        }
    }
}

class SingleClientMultiDL extends DLTests {
    "Multiple concurrent downloads" should {
        "all result in files with their original contents" in {
            val clients = makeFastClients(3)
            val testLocs = testfileNames map fromTo

            // for each test file
            for ((filename, loc) <- testfileNames zip testLocs) {

                // ensure the file to download doesn't already exist
                loc.to.toFile.delete()

                // peers should have file locally
                clients.tail foreach (_.underlyingActor.loadFile(loc.from, filename))

                // register test agent to listen for `DownloadSuccess`
                clients.head.underlyingActor.listeners += self

                // client init's download
                val info = LocalP2PFile.loadFile(filename, loc.from).fileInfo
                clients.head ! FileToDownload(info, clients.tail.toSet, Set.empty)
            }

            /* see if it worked */
            within(5 seconds) {
                expectMsgAllOf(testfileNames map DownloadSuccess:_*)
            }
            for (loc <- testLocs)
                assert(filesEqual(loc))
        }
    }
}

class MultiClientMultiDL extends DLTests {
    "Multiple concurrent downloads" should {
        "result in files with their original contents" in {

            // these guys will serve the files
            val peers = makeFastClients(2)

            // these guys will download the files
            // the names 1 and 2 were already taken above
            val clients = (3 to 4) map { i =>
                TestActorRef(
                    props = Client.props(downloadsDir = s"client-$i-dl"),
                    name = s"client-$i"
                )
            } map { _.asInstanceOf[TestActorRef[Client]] }

            // each client is associated with a set of pairs defining which
            // "real" file they're downloading and where they should download it to
            val clientLocs = clients.map(i => mutable.Set.empty[Loc])
            for (filename <- testfileNames) {
                val from = fromDir / filename

                // peers each load all the files
                peers.foreach(_.underlyingActor.loadFile(from, filename))

                // get file download metadata
                val info = LocalP2PFile.loadFile(filename, from).fileInfo

                // all clients download all the files
                clients.zip(clientLocs).foreach { case (client, clientLoc) =>
                    val clientDlDir = Path(client.underlyingActor.downloadDir)
                    val loc = Loc(from, clientDlDir/filename)
                    clientLoc += loc
                    loc.to.toFile.delete()
                    client.underlyingActor.listeners += self
                    client ! FileToDownload(info, peers.toSet, Set.empty)
                }
            }

            // see if it worked
            within(5 seconds) {
                expectMsgAllOf(
                    (
                        for {
                            client <- clients
                            filename <- testfileNames
                        } yield DownloadSuccess(filename)
                    ):_*
                )
            }
            for (locs <- clientLocs; loc <- locs)
                assert(filesEqual(loc))
        }
    }
}
