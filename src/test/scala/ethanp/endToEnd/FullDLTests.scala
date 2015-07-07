package ethanp.endToEnd

import java.io.File

import akka.actor.{ActorRef, Props}
import akka.testkit.TestActorRef
import ethanp.actors.BaseTester
import ethanp.file.{FileToDownload, LocalP2PFile}
import ethanp.firstVersion._

import scala.concurrent.duration._
import scala.io.Source._
import scala.language.postfixOps

/**
 * Created by Ethan Petuchowski on 7/6/15.
 *
 */
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
class FullDLTests extends DLTests {

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
            within (5 seconds) {
                expectMsg(DownloadSuccess(filename))
            }
            assert(filesEqual(from, to))
        }
    }

    "downloading a file" should {
        val dir = new File("downloads")
        if (dir.exists()) dir.delete()

        val client = TestActorRef(Props[Client])

//        client !

        "create the download directory" in {
            dir should exist
        }
//        "create the file" ignore {}
//        "eventually own the full file" ignore {}
    }
}
