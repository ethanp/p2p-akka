package ethanp.endToEnd

import java.io.File

import akka.actor.{ActorRef, Props}
import akka.testkit.TestActorRef
import ethanp.actors.BaseTester
import ethanp.file.{FileToDownload, LocalP2PFile}
import ethanp.firstVersion._
import org.scalatest.Suites

import scala.concurrent.duration._
import scala.io.Source._
import scala.language.postfixOps

/**
 * Created by Ethan Petuchowski on 7/6/15.
 *
 */
class FullDLTests extends Suites(
    new SingleDL,
    new MultiDL
)

class DLTests extends BaseTester {
    def filesEqual(path1: String, path2: String): Boolean =
        fromFile(path1).mkString == fromFile(path2).mkString

    def makeActors(num: Int, props: Props, name: String): Vector[ActorRef] =
        (0 until num).map(i => TestActorRef(props, s"$name-$i")).toVector

    def makeClients(num: Int) = makeActors(num, Props[Client], "client").map(_.asInstanceOf[TestActorRef[Client]])
    def makeTrackers(num: Int) = makeActors(num, Props[Tracker], "tracker").map(_.asInstanceOf[TestActorRef[Tracker]])
    val fromDir = "testfiles"
    val toDir = "downloads"
    def fromTo(filename: String) = s"$fromDir/$filename" -> s"$toDir/$filename"
}

class SingleDL extends DLTests {

    "A downloaded file" should {
        "have the same contents" in {
            val clients = makeClients(3)
            val filename = "Test1.txt"
            val (from, to) = fromTo(filename)
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
class MultiDL extends DLTests {
    "Multiple concurrent downloads" should {
        "result in files with their original contents" in {
            val clients = makeClients(3)

            val filenames = (2 to 3).map(i => s"input$i.txt") :+ "Test1.txt"

            var locs = Set.empty[(String, String)]

            for (filename <- filenames) {
                val ft: (String, String) = fromTo(filename)
                locs += ft
                val info = LocalP2PFile.loadFile(filename, ft._1).fileInfo
                new File(ft._2).delete()

                clients.tail foreach (_.underlyingActor.loadFile(ft._1, filename))
                clients.head.underlyingActor.notificationListeners += self
                clients.head ! FileToDownload(info, clients.tail.toSet, Set.empty)
            }
            // see if it worked
            within(5 seconds) {
                expectMsgAllOf(filenames map DownloadSuccess:_*)
            }
            locs foreach {
                case (from, to) =>
                    assert(filesEqual(from, to))
            }
        }
    }
}
