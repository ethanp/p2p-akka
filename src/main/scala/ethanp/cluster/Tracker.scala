package ethanp.cluster

import akka.actor._
import akka.util.Timeout
import ethanp.cluster.ClusterUtil._

import scala.collection.{SortedSet, immutable, mutable}
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.util.Random

/**
 * Ethan Petuchowski
 * 4/9/15
 *
 * Upon creation, it connects to all other servers in the system
 */
class Tracker extends GroupMem {

    implicit val timeout = Timeout(5.seconds)

    /**
     * Set to `true` by `Pause` from Master
     * prevents `antiEntropizeAll()` from doing anything
     *
     * this would be called after receiving
     *      1. `Put` from Client
     *      2. `Delete` from Client
     *      3. `UpdateWrites` from another Server
     *
     * On receiving `Start` from Master, this becomes `false` and we `antiEntropizeAll()`
     */
    var isPaused = false

    /**
     * Set to `true` if
     *      1. we are the first server Up
     *      2. we receive `URPrimary` from a retiring primary
     *
     * It means we commit
     *      1. `Put`s and `Delete`s from Client
     *      2. All uncommitted incoming updates from other Servers
     */
    var isPrimary = false

    /**
     * When `true`, I will 'retire' (i.e. stop existing)
     * after sending all my updates to the next Server to ask
     */
    var isRetiring = false

    /**
     * Made true via Master `Stabilize` msg initiated via command line
     * When true we inform Master whenever we `antiEntropizeAll()`
     *      so that it knows the system is still 'unstable'
     */
    var isStabilizing = false

    /**
     * The _Command Line_ name referring to this Server
     */
    override var nodeID: NodeID = _

    /**
     * The _Bayou_ name assigned by another Server in-the-know
     */
    var serverName: ServerName = _

    /** Couldn't've said it better myself */
    var myVersionVector = new MutableVV

    /**
     * The highest Commit Stamp Number I have seen
     *
     * TODO this may need better synchronization...
     * Although actually, I think only one incoming msg gets processed at a time,
     *      so maybe I won't have concurrency issues?
     */
    @volatile var csn: LCValue = 0

    /**
     * Will later be initialized to TCP address of Master
     * who runs at a fixed address (localhost:2552)
     * but the nodes pretend not to know that
     */
    var masterRef: ActorRef = _

    /**
     * Set of clients I am responsible for telling that I am retiring
     */
    var clients = Map.empty[NodeID, ActorRef]

    /**
     * Only one incoming msg gets processed at a time
     * so I'm unlikely to have concurrency issues
     */
    var writeLog = SortedSet.empty[Write]

    /**
     * TCP addresses of Servers I will anti-entropize, or pronounce leader when I step-down
     */
    var connectedServers = Map.empty[NodeID, ActorSelection]

    /**
     * All TCP addresses of any servers I have ever seen.
     * Some of them may not be alive any more, and at this point I don't remove those.
     */
    var knownServers = Map.empty[NodeID, ActorSelection]

    def getMyLCValue: LCValue = myVersionVector(serverName)
    def incrementMyLC(): LCValue = myVersionVector increment serverName

    /**
     * also ensures that the chosen server exists (it may have retired already)
     * this will hang if there are other servers in existence...
     */
    def getRandomServer: (NodeID, ActorSelection) = {
        while (true) {
            val (id: NodeID, actSel: ActorSelection) = Random.shuffle(connectedServers).head
            try {
                val futureRef: Future[ActorRef] = actSel.resolveOne(2 seconds)
                // Await.result throws a TimeoutException if `f` is not available after 2 seconds.
                // Also an exception will be thrown if futureRef is "failed" because
                //  the desired server does not exist,
                Await.result(futureRef, 2 seconds)
                return id → actSel
            }
            catch { case e : Throwable ⇒
                /* NB: we don't remove from connectedServers bc that's not part of the protocol */
            }
        }
        null
    }

    def appendWrite(write: Write): Write = { writeLog += write ; write }
    def appendAction(action: Action): Write = appendWrite(makeWrite(action))
    def greaterOf(items: LCValue*): LCValue = Seq(items:_*).max
    def tellMasterImUnstable(): Unit = if (isStabilizing) masterRef ! Updating

    /**
     * For now we just play through to build database state upon request...
     */
    def getSong(songName: String): Song = {

        /* "play through" log into this thing */
        val state = mutable.Map.empty[String, URL]
        for (w ← writeLog) {
            w.action match {
                case Put(_, name, url) => state(name) = url
                case Delete(_, name)   => state remove name
                case _ ⇒ // ignore creations and retirement writes
            }
        }
        if (state contains songName) Song(songName, state(songName))
        else Song(songName, "ERR_KEY") // see assn-spec §4.2 Get format
    }

    def makeWrite(action: Action): Write =
        if (isPrimary) Write(nextCSN(), nextAcceptStamp(), action)
        else Write(INF, nextAcceptStamp(), action)

    def nextCSN(): LCValue = {
        csn += 1
        csn
    }

    def nextAcceptStamp() = AcceptStamp(serverName, incrementMyLC())

    def appendAndSync(action: Action): Option[Write] = {
        if (!isRetiring) {
            val write = appendAction(action)
            antiEntropizeAll()
            Some(write)
        }
        else None
    }

    /**
     * Initiates anti-entropy sessions with all `connectedServers`
     * Called after writing to the `writeLog`
     */
    def antiEntropizeAll(): Unit = if (!isPaused) broadcastServers(LemmeUpgradeU)

    /**
     * will still anti-entropize even if we are `isPaused`
     */
    def antiEntropizeWith(actorSelection: ActorSelection): Unit = actorSelection ! LemmeUpgradeU

    def broadcastServers(msg: Msg): Unit = connectedServers.values foreach (_ ! msg)

    /**
     * Apply `f` to the node specified in the `Forward2` who is not me
     */
    def otherIDAndRespond[T](w: NetworkPartition, f: NodeID ⇒ T): Unit =
        if (w.i == nodeID) {
            f(w.j)
            masterRef ! Gotten
        }
        else f(w.i)

    /**
     * From (Lec 11, pg. 6)
     * If "S" (me) finds out that R_i (`vec`s owner) doesn't know of R_j = (TS_{k,j},R_k)
     *    - If `vec(R_k) ≥ TS_{k,j}`, don't forward writes ACCEPTED by R_j
     *    - Else send R_i all writes accepted by R_j
     *
     * @return all updates strictly after the given `versionVector` and commits since CSN
     */
    def allWritesSince(theirVV: ImmutableVV, theirCSN: LCValue) = UpdateWrites(
        immutable.SortedSet.empty[Write] ++ writeLog filter { write ⇒
            def acceptedSince = theirVV isOlderThan write.acceptStamp
            def newlyCommitted = write.isCommitted && write.commitStamp > theirCSN
            acceptedSince || newlyCommitted
        }
    )

    def createServer(c: CreateServer) = {
        val servers = c.servers filterNot (_._1 == nodeID)
        if (servers.isEmpty) {
            log.info("assuming I'm first server, becoming primary with name '0'")

            // assume role of primary
            isPrimary = true

            // assign my name
            serverName = AcceptStamp(null, 0)

            // init my VersionVector
            myVersionVector = new MutableVV(mutable.Map(serverName → 0))

            masterRef ! IExist(nodeID)
        }
        else {
            // save existing servers
            connectedServers ++= servers map { case (id, path) ⇒
                id → getSelection(path)
            }
            knownServers ++= connectedServers

            // tell them that I exist
            broadcastServers(IExist(nodeID))

            /**
             * "A Bayou server S_i creates itself by sending a 'creation write' to another server S_k
             *  Any server for the database can be used."
             */
            connectedServers.head._2 ! CreationWrite
        }
    }

    def retireServer(server: RetireServer): Unit = {

        /* This disables future writes from being appended to the log */
        appendAction(Retirement(serverName))

        val randomServer = getRandomServer
        if (isPrimary) {

            /* find another primary */

            // TODO this could return a non-existent server!!
            // I'm not sure yet what to do about that...
            randomServer._2 ! URPrimary

            // step down
            isPrimary = false
        }
        isRetiring = true
        antiEntropizeWith(randomServer._2)

        /** THEY TOOK THIS OUT OF THE PROJECT.
         * tell my clients of a different server to connect to
         * though if by the time the client connects the NEW server has already retired
         * there are going to be issues. But I don't think they'll test that.
         */
//        val serverGettingClients = getRandomServer
//        printIf(s"server $nodeID sending clients a new daddy, viz ${serverGettingClients._1}")
//        clients foreach (_._2 ! ServerSelection(id=serverGettingClients._1, sel=serverGettingClients._2))



        /* NB: after they respond with their VV and I send them my updates, I will leave the cluster */
    }

    /**
     * someone sent me writes ordered after myVersionVector, so I need to
     *  1. Ignore what I already have
     *  2. Tell the Master that I'm "unstable"
     *  3. Stamp writes if I'm primary
     *  4. Accept new commits*/
    def updateWrites(w: UpdateWrites): Unit = {

        val writeLogAcceptStamps: Map[ServerName, LCValue] =
            writeLog.toList.map(w ⇒ w.acceptStamp → w.commitStamp).toMap

        if (w.writes.isEmpty) return

        // ignore anything I have verbatim, or if I have the committed version already
        val rcvdWrites = w.writes filterNot { w ⇒
            def haveWriteVerbatim = writeLog contains w
            def haveMatchingAcceptStamp = writeLogAcceptStamps contains w.acceptStamp
            def itIsCommitted = writeLogAcceptStamps(w.acceptStamp) < INF
            def haveCommittedVersion = haveMatchingAcceptStamp && itIsCommitted

            haveWriteVerbatim || haveCommittedVersion
        }

        if (rcvdWrites.isEmpty) return

        tellMasterImUnstable()

        /* 1st: all elements satisfying the predicate
         * 2nd: all elements that don't
         */
        val (rcvdCommits, notCommitted) = rcvdWrites partition (_.isCommitted)

        /* incorporate received committed-writes */
        if (rcvdCommits.nonEmpty) {

            /* update csn */
            val highestRcvdCommitStamp = (rcvdCommits maxBy (_.commitStamp)).commitStamp
            csn = greaterOf(csn, highestRcvdCommitStamp)

            /* remove "tentative" writes that have "committed" */
            val newTimestamps = (rcvdCommits map (_.acceptStamp)).toSet
            writeLog = writeLog filterNot (w ⇒ w.tentative && (newTimestamps contains w.acceptStamp))

            writeLog ++= rcvdCommits
        }

        if (notCommitted.nonEmpty) {
            /* For primary, this must happen AFTER adding rcvd commits so that csn's don't conflict.
             * Although there may STILL be a chance for conflicts, not sure.
             * ...I'm going to hope I don't have to worry about this.... */
            if (isPrimary) writeLog ++= (notCommitted map (_ commit nextCSN))
            else writeLog ++= notCommitted
        }

        /* we're not passing the newlyCommitted version into this, but it doesn't affect anything */
        myVersionVector updateWith rcvdWrites

        /* propagate received writes if there was anything new ("gossip") */
        antiEntropizeAll()
    }

    def creationWrite(): Unit = {

        // "accept" a creation write (and commit it if `isPrimary`)
        val creationWrite = makeWrite(CreationWrite)

        // use it to name the new server
        val serverName = creationWrite.acceptStamp

        // add creation to writeLog
        appendWrite(creationWrite)

        // add them to the version vector
        myVersionVector addNewMember (serverName → getMyLCValue)

        /**
         * Tell Them:
         *  1. their new name
         *  2. my current writeLog (I don't think we must synchronize for serializing this)
         *  3. my current CSN
         *  4. my VV
         */
        sender ! GangInitiation(serverName, writeLog, csn, ImmutableVV(myVersionVector))
    }

    override def handleMsg: PartialFunction[Msg, Unit] = {

        /**
         * "Breaking the connection" here means I never send them anything.
         * Different nodes don't generally maintain longstanding TCP connections,
         * they only create a socket connection when they are actively messaging each other;
         * so there is no connection to physically break.
         * They all remain connected to the macro-cluster at all times.
         */
        case fwd@BreakConnection(id1, id2) ⇒
            otherIDAndRespond(fwd, connectedServers -= _)

        case fwd@RestoreConnection(id1, id2) ⇒
            otherIDAndRespond(fwd, id ⇒ connectedServers += id → knownServers(id))


        /** prevents antiEntropization */
        case Pause ⇒ isPaused = true

        /** resumes antiEntropization */
        case Start ⇒
            isPaused = false
            antiEntropizeAll()

        /* tell the master upon receiving updates */
        case Stabilize ⇒
            isStabilizing = true
            antiEntropizeAll()

        case DoneStabilizing ⇒ isStabilizing = false

        /** for debugging */
        case Hello ⇒
            println(s"""server $nodeID
                       |--------------
                       |connected servers: ${connectedServers.keys.toList}
                       |connected clients: ${clients.keys.toList}
                       |log: ${writeLog.toList}
                       |VV: $myVersionVector
                       |csn: $csn"""
                    .stripMargin)

        case CreationWrite ⇒ creationWrite()

        case URPrimary ⇒ isPrimary = true

        case GangInitiation(name, log, commNum, vv) ⇒
            serverName = name
            writeLog   = log
            csn        = commNum
            myVersionVector = MutableVV(vv)
            masterRef  ! IExist(nodeID)

        /* "<T_{k,i}, S_k> becomes S_i’s server-id." */
        case s: ServerName ⇒ serverName = s

        /**
         * Received by a server who was just added to the 'macro-cluster',
         * uses this info to fully become one of the gang.
         */
        case m: CreateServer ⇒ createServer(m)

        /**
         * Received from the Master.
         * Instructs me to go through the retirement procedure,
         * then exit the macro-cluster.
         * My nodeID will never be used again.
         */
        case m: RetireServer ⇒ retireServer(m)

        /**
         * Print my complete `writeLog` to `StdOut` in the specified format
         */
        case PrintLog(id) ⇒
            writeLog.toList flatMap (_.strOpt) foreach println
            printIf(writeLog)
            masterRef ! Gotten

        /**
         * The Master has assigned me a logical id
         * which is used hereafter on the command line to refer to me.
         *
         * While we're at it, save a reference to the master
         */
        case IDMsg(id) ⇒
            masterRef = sender()
            nodeID = id
            log info s"server id set to $nodeID"

        /**
         * Client has submitted new log entry that I should replicate
         *
         * "There are write session guarantees that must be met.
         *  If a server is unable to meet these write guarantees
         *       then the write should be dropped" -- https://piazza.com/class/i5h1h4rqk9t4si?cid=90
         */
        case ClientWrite(wVec, rVec, req) ⇒
            // check vv against session constraints and update it
            var updatedWVec = wVec
            if (!(myVersionVector dominates wVec))
                printIf(s"server $nodeID's VV is older than wVec")
            else if (!(myVersionVector dominates rVec))
                printIf(s"server $nodeID's VV is older than rVec")
            else if (!(clients.keySet contains req.cliID))
                printIf(s"server $nodeID doesn't know about client ${req.cliID}")
            else {
                tellMasterImUnstable()
                appendAndSync(req)
                updatedWVec = wVec.update(serverName, getMyLCValue)
            }
            sender ! NewVVs(updatedWVec, rVec)

        /**
         * Send the client back the Song they requested
         * Returns ERR_DEP when this server doesn't know it "owns" this client yet
         * Also sends them their updated VVs, upon reception of which they unblock the Master
         */
        case ClientGet(wVec, rVec, req) =>
            val Get(clientID, songName) = req
            val song = getSong(songName)
            if ((myVersionVector dominates wVec) &&
                 (myVersionVector dominates rVec) &&
                 (clients contains clientID))
            {
                log info s"getting song $songName"
                log info s"found song $song"
                sender ! song
                sender ! NewVVs(wVec, ImmutableVV(myVersionVector))
            }
            else {
                sender ! Song(songName, "ERR_DEP")
                sender ! NewVVs(wVec, rVec)
            }

        /**
         * After calling this, no new nodes can be added to the cluster.
         * I don't understand what the difference is between
         *      the Cluster object
         *      the context
         *      the context.system
         *      etc.
         *   so I don't know how to handle this properly...
         */
        case KillEmAll ⇒
            printIf(s"server $nodeID shutting down")
            context.system.shutdown()

        /**
         * Received by all connected servers from a new server in the macro-cluster
         * who wants to receive epidemics just like all the cool kids.
         */
        case IExist(id) ⇒
            // I think the paren's are just there to remind you NOT to "close over" the `sender()`
            val serverPair = id → getSelection(sender().path)
            connectedServers += serverPair
            knownServers += serverPair

        /**
         * Sent by a client for whom this server is the only one it knows.
         *
         *  Q: What about if this guy crashes?
         *  A: Nodes cannot simply 'crash'
         */
        case ClientConnected(id) ⇒
            clients += (id → sender)
            sender ! serverName

        /**
         * Proposition from someone else that they want to update all of my knowledges.
         * So I gotta tell em where I'm at.
         */
        case LemmeUpgradeU ⇒ sender ! CurrentKnowledge(ImmutableVV(myVersionVector), csn)

        case Gotten ⇒ masterRef ! Gotten

        /**
         * Bayou Haiku:
         * ------------
         * Tell me what you've heard,
         * I will tell you what I know,
         * where I know you don't.
         */
        case CurrentKnowledge(vec, commitNo) ⇒
            sender ! allWritesSince(vec, commitNo)
            if (isRetiring) {

                /* unblocks Master waiting on my retirement to complete */
                sender ! Gotten

                /* Akka API gives two options for leaving the Cluster:
                 *   `context stop self` --- exit when `receive` method returns
                 *   `self ! PoisonPill` --- append PoisonPill to mailbox, exit upon receipt
                 */
                context stop self
            }

        /**
         * Those writes they thought I didn't know,
         *   including things I knew only "tentatively" that have now "committed".
         * May also include things I already know by now (those get filtered out).
         */
        case m: UpdateWrites ⇒ updateWrites(m)
    }
}


object Tracker {
    def main(args: Array[String]): Unit = ClusterUtil joinClusterAs "server"
}
