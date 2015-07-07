package ethanp.endToEnd

import java.io.File

import akka.actor.{ActorRef, Props}
import akka.testkit.TestActorRef
import ethanp.actors.BaseTester
import ethanp.file.{FileToDownload, LocalP2PFile}
import ethanp.firstVersion._
import org.scalatest.Suites

import scala.collection.mutable
import scala.concurrent.duration._
import scala.io.Source._
import scala.language.postfixOps

/**
 * Created by Ethan Petuchowski on 7/6/15.
 *
 */
class FullDLTests extends Suites(
    new SingleDL,
    new SingleClientMultiDL
)

class DLTests extends BaseTester {
    case class Loc(from: String, to: String)
    def filesEqual(path1: String, path2: String): Boolean =
        fromFile(path1).mkString == fromFile(path2).mkString

    var actorsGenerated = 0

    def makeActors(num: Int, props: Props, name: String): Vector[ActorRef] =
        (0 until num).map { i =>
            actorsGenerated += 1
            TestActorRef(props, s"$name-$actorsGenerated")
        }.toVector

    def makeClients(num: Int) = makeActors(num, Props[Client], "client").map(_.asInstanceOf[TestActorRef[Client]])
    def makeTrackers(num: Int) = makeActors(num, Props[Tracker], "tracker").map(_.asInstanceOf[TestActorRef[Tracker]])
    val fromDir = "testfiles"
    def fromTo(filename: String) = Loc(s"$fromDir/$filename", s"downloads/$filename")
    val testfileNames = (2 to 3).map(i => s"input$i.txt") :+ "Test1.txt"
}

class SingleDL extends DLTests {

    "A downloaded file" should {
        "have the same contents" in {
            val clients = makeClients(3)
            val filename = "Test1.txt"
            val Loc(from, to) = fromTo(filename)
            val info = LocalP2PFile.loadFile(filename, from).fileInfo
            new File(to).delete()

            clients.tail foreach (_.underlyingActor.loadFile(from, filename))
            Thread sleep 100 // let them load & hash the file

            clients.head.underlyingActor.notificationListeners += self

            // remaining client downloads file
            clients.head ! FileToDownload(info, clients.tail.toSet, Set.empty)
            Thread sleep 150

            // see if it worked
            within(5 seconds) {
                expectMsg(DownloadSuccess(filename))
            }
            assert(filesEqual(from, to))
        }
    }
}
class SingleClientMultiDL extends DLTests {
    "Multiple concurrent downloads" should {
        "result in files with their original contents" in {
            val clients = makeClients(3)
            val filenames = (2 to 3).map(i => s"input$i.txt") :+ "Test1.txt"
            var locs = Set.empty[Loc]
            for (filename <- filenames) {
                val loc = fromTo(filename)
                locs += loc
                val info = LocalP2PFile.loadFile(filename, loc.from).fileInfo
                new File(loc.to).delete()
                clients.tail foreach (_.underlyingActor.loadFile(loc.from, filename))
                clients.head.underlyingActor.notificationListeners += self
                clients.head ! FileToDownload(info, clients.tail.toSet, Set.empty)
            }
            // see if it worked
            within(5 seconds) {
                expectMsgAllOf(filenames map DownloadSuccess:_*)
            }
            locs foreach { i =>
                assert(filesEqual(i.from, i.to))
            }
        }
    }
}

class MultiClientMultiDL extends DLTests {
    "Multiple concurrent downloads" should {
        "result in files with their original contents" in {
            val clients = makeClients(2)
            def cToDir(name: String) = s"downloads_$name"
            val peers = makeClients(2)
            val clientLocs = clients.map(i => mutable.Set.empty[Loc])
            for (filename <- testfileNames) {
                val from = s"$fromDir/$filename"
                def tof(name: String) = s"${cToDir(name)}/$filename"
                val info = LocalP2PFile.loadFile(filename, from).fileInfo
                peers foreach { p =>
                    p.underlyingActor.loadFile(from, filename)
                }
                clients.zip(clientLocs).foreach { case (client, clientLoc) =>
                    val loc = Loc(from, tof(client.path.name))
                    clientLoc += loc
                    new File(loc.to).delete()
                    client.underlyingActor.notificationListeners += self
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
            clientLocs foreach { locs =>
                locs foreach { i =>
                    assert(filesEqual(i.from, i.to))
                }
            }
        }
    }
}