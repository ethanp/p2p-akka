package ethanp.firstVersion

import akka.actor.{Actor, ActorLogging, ActorRef}
import ethanp.file.FileToDownload

import scala.collection.mutable

/**
 * Ethan Petuchowski
 * Created 6/4/15
 *
 * From the Tracker's perspective, it doesn't matter how clients
 * "interested in the file" are split between seeders and leechers;
 * but in the eyes of a prospective downloader, it may matter.
 */
class Tracker extends Actor with ActorLogging {
    log.info(s"tracker $self starting up")

    /** **ONLY** for use by Master scripter */
    val knowledgeOf = mutable.Map.empty[String, FileToDownload]

    // I would like to know a better way to do this while keeping things immutable
    def addSeeder(filename: String, sndr: ActorRef) { knowledgeOf(filename) = knowledgeOf(filename).seed_+(sndr) }
    def addLeecher(filename: String, sndr: ActorRef) { knowledgeOf(filename) = knowledgeOf(filename).leech_+(sndr) }
    def subtractSeeder(filename: String, sndr: ActorRef) { knowledgeOf(filename) = knowledgeOf(filename).seed_-(sndr) }
    def subtractLeecher(filename: String, sndr: ActorRef) { knowledgeOf(filename) = knowledgeOf(filename).leech_-(sndr) }


    override def receive: Receive = {
        case m: ListTracker => sender ! TrackerKnowledge(knowledgeOf.values.toList)

        case InformTrackerIHave(info) =>

            val desiredFilename = info.filename
            def hashMatches = knowledgeOf(desiredFilename).fileInfo == info
            def alreadyKnown = knowledgeOf contains desiredFilename
            def replyDifferentFileExists() = sender ! TrackerSideError(s"different file named $desiredFilename already tracked")

            if (alreadyKnown) {
                if (!hashMatches) {
                    replyDifferentFileExists
                }
                else {
                    val swarm = knowledgeOf(desiredFilename)
                    if (swarm.seeders.contains(sender())) {
                        sender ! TrackerSideError("already knew you are seeding this file")
                    }
                    else if (swarm.leechers.contains(sender())) {
                        subtractSeeder(desiredFilename, sender())
                        addLeecher(desiredFilename, sender())
                        sender ! SuccessfullyAdded(desiredFilename)
                    }
                    else {
                        addSeeder(desiredFilename, sender())
                        sender ! SuccessfullyAdded(desiredFilename)
                    }
                }
            }
            else {
                knowledgeOf(desiredFilename) = FileToDownload(info, Set(sender()), Set())
                sender ! SuccessfullyAdded(desiredFilename)
            }

        case DownloadFile(filename) =>
            if (knowledgeOf contains filename) {
                sender ! knowledgeOf(filename) // msg is of type [FileToDownload]
                addLeecher(filename, sender())
                if (knowledgeOf(filename).seeders.contains(sender()))
                    subtractSeeder(filename, sender())
            }
            else sender ! TrackerSideError(s"I don't know a file called $filename")
    }
}
