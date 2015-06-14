package ethanp.integration

import java.io.File

import akka.actor.Props
import ethanp.integration.BaseTester.ForwardingActor

/**
 * Ethan Petuchowski
 * 6/14/15
 */
class Level2 extends BaseTester {
    /* TODO how can I make writing these tests get me to the END goal FASTER?
     * with more focus on speed and less focus on absolute robustness
     * TODO I need to keep tests at a higher level
     * this will lead to easier refactoring in these beginning stages anyway
     */
    "a FileDownloader" when {
        "there are 5 seeders and 5 leechers" when {
            val fwdActors = (1 to 10).map(i => system.actorOf(Props(classOf[ForwardingActor], self)))
            val seeders = (fwdActors take 5).toSet
            val leechers = (fwdActors drop 5).toSet
//            val ftd = FileToDownload(testTextP2P.fileInfo, seeders, leechers)
            val liveSeeders = seeders take 3
            val deadSeeders = seeders drop 3
            val liveLeechers = leechers take 2
            val deadLeechers = leechers drop 2
            val dlDir = new File("test_downloads")
            dlDir.deleteOnExit()
//            val fileDLRef = TestActorRef(new FileDownloader(ftd, dlDir))
            "first starting up" should {
                "check which peers are alive" when {
                    "2 seeders and 3 leechers are down" ignore {

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
