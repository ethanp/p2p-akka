package ethanp.backend.client

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

    /**
      * Maps filenames to lists of available Seeders and Leechers
      */
    val knowledgeOf = mutable.Map.empty[String, FileToDownload]

    override def receive: Receive = {
        case m: ListTracker => sender ! TrackerKnowledge(knowledgeOf.values.toList)

        case InformTrackerIHave(info) =>

            val desiredFilename = info.filename
            def hashMatches = knowledgeOf(desiredFilename).fileInfo == info
            def filenameKnown = knowledgeOf contains desiredFilename
            def replySuccess() = sender ! SuccessfullyAdded(desiredFilename)
            def successfullyAddToSeeders() {
                addSeeder(desiredFilename, sender())
                replySuccess()
            }

            if (!filenameKnown) {
                knowledgeOf(desiredFilename) = FileToDownload(
                    fileInfo = info,
                    seeders = Set.empty,
                    leechers = Set.empty
                )
                successfullyAddToSeeders()
            }
            else if (!hashMatches) {
                replyWithError(s"different file named $desiredFilename already tracked")
            }
            else {
                val swarm = knowledgeOf(desiredFilename)
                if (swarm.seeders contains sender) {
                    replyWithError("already knew you are seeding this file")
                } else {
                    if (swarm.leechers contains sender) {
                        subtractLeecher(desiredFilename, sender())
                    }
                    successfullyAddToSeeders()
                }
            }

        case DownloadFile(filename) =>
            if (!(knowledgeOf contains filename))
                replyWithError(s"I don't know a file called $filename")
            else {
                sender ! knowledgeOf(filename) // msg is of type `FileToDownload`
                addLeecher(filename, sender())
                if (knowledgeOf(filename).seeders contains sender)
                    subtractSeeder(filename, sender())
            }
    }

    def replyWithError(s: String): Unit = sender ! TrackerSideError(s)

    def addLeecher(filename: String, sndr: ActorRef) { knowledgeOf(filename) = knowledgeOf(filename) leech_+ sndr }

    def subtractSeeder(filename: String, sndr: ActorRef) { knowledgeOf(filename) = knowledgeOf(filename) seed_- sndr }

    // I would like to know a better way to do this while keeping things immutable
    def addSeeder(filename: String, sndr: ActorRef) { knowledgeOf(filename) = knowledgeOf(filename) seed_+ sndr }

    def subtractLeecher(filename: String, sndr: ActorRef) { knowledgeOf(filename) = knowledgeOf(filename) leech_- sndr }
}
