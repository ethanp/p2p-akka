package ethanp.integration

import java.io.File

import akka.actor.Props
import akka.testkit.TestActorRef
import ethanp.file.FileToDownload
import ethanp.firstVersion.{Ping, FileDownloader}
import ethanp.integration.BaseTester.ForwardingActor

/**
 * Ethan Petuchowski
 * 6/14/15
 */
class FileDownloaderTests extends BaseTester {

    /* TODO Tests are a MEANS to an END
     *      no need to focus on 'absolute robustness'
     * TODO keep tests at a higher level
     *      this will make refactoring simpler
     */

    "a FileDownloader" when {
        "there are 5 seeders and 5 leechers" when {
            val fwdActors = (1 to 10).map(i => system.actorOf(Props(classOf[ForwardingActor], self))).toSet
            val (seeders, leechers) = splitAtIndex(fwdActors, 5)
//            val ftd = FileToDownload(testTextP2P.fileInfo, seeders, leechers)

            "2 seeders and 3 leechers are down" when {
                val (liveSeeders, deadSeeders) = splitAtIndex(seeders, 3)
                val (liveLeechers, deadLeechers) = splitAtIndex(leechers, 2)
                val dlDir = new File("test_downloads")
                dlDir.deleteOnExit()

                val fileInfo = testTextP2P.fileInfo
                val ftd = FileToDownload(fileInfo, seeders, leechers)

                "first starting up" should {
                    val fDlRef = TestActorRef(Props(Class[FileDownloader], ftd, dlDir))
                    "check which peers are alive" when {
                        quickly {
                            expectNOf(10) {
                                Ping(fileInfo.abbreviation)
                            }
                        }
                    }
                }
            }
            "set aside peers who don't respond" ignore {
                // look at internal state
            }
            "" ignore {}
        }
    }
}
