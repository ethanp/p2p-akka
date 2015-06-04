package ethanp.cluster

import java.lang.System.err

import akka.actor._
import akka.cluster.ClusterEvent._
import akka.cluster.{Cluster, Member}
import ethanp.cluster.ClusterUtil._
import ethanp.cluster.Master.handleNext

import scala.concurrent.duration._
import scala.sys.process._

/**
 * Ethan Petuchowski
 * 4/9/15
 *
 * Receives commands from the command line and deals with them appropriately.
 *
 * Also the "first seed node" i.e. the one that all actors attempting
 * to join the cluster contact first.
 */
object Master {

    def main(args: Array[String]) {}

    /**
     * Create a new process that will join the macro-cluster as the given type
     * @return the process reference
     */
    def createClientProc(): Process = s"sbt --warn execClient".run()
    def createServerProc(): Process = s"sbt --warn execServer".run()

    /**
     * Create a new Client Actor (inside this process) that will join the macro-cluster.
     * It won't know it's console-ID yet, we'll tell it that once it joins the cluster.
     */
    def createClient(cid: NodeID, sid: NodeID) {
        clusterKing ! NewClient(cid, sid)
        Client.main(Array.empty)
    }

    /**
     * Create a new Server Actor (inside this process) that will join the macro-cluster.
     * It won't know it's console-ID yet, we'll tell it that once it joins the cluster.
     */
    def createTracker(sid: NodeID) {
        clusterKing ! NewTracker(sid)
        Tracker.main(Array.empty)
    }

    /**
     * Make the Master Actor the first seed node in the Cluster, i.e. the one standing by
     * waiting for new nodes to ask to join the cluster so that it can say a resounding YES!
     */
    val clusterKing = ClusterUtil.joinClusterAs("2551", "master")

    /**
     * THE COMMAND LINE INTERFACE
     * --- now, with blocking!
     */
    val sc = new java.util.Scanner(System.in)

    /**
     * we only handle one line at a time,
     * otherwise we are in free-space simply waiting for it to complete,
     * in the callback handler, we try to handle the next line
     */
    def handleNext: Unit = {
        printIf("handling next")
        if (sc.hasNextLine) handle(sc.nextLine)
        else ClusterUtil.systems.foreach(_.shutdown())
    }

    /**
     * Sends command-line commands to the Master Actor as messages.
     * Would theoretically work even if the CLI and Master were on different continents.
     */
    def handle(str: String): Unit = {

        val brkStr = str split " "
        printIf(s"handling { $str }")
        lazy val b1 = brkStr(1) // 'lazy' because element does not exist unless it is needed
        lazy val b2 = brkStr(2)
        lazy val b1i = b1.toInt
        lazy val b2i = b2.toInt
        brkStr head match {

            /* shiny new p2p-related stuff */
            case "addLocalFile" ⇒ clusterKing ! AddLocalFile(cid = b1i, filePath = b2)


            /* still useful for testing */
            case "joinTracker"       ⇒ createTracker(sid = b1i)
            case "joinClient"        ⇒ createClient(cid = b1i, sid = b2i)

            /* old crap that I don't need anymore */
            case "retireTracker"     ⇒ clusterKing ! RetireTracker(id = b1i)
            case "breakConnection"   ⇒ clusterKing ! BreakConnection(id1 = b1i, id2 = b2i)
            case "restoreConnection" ⇒ clusterKing ! RestoreConnection(id1 = b1i, id2 = b2i)
            case "pause"             ⇒ clusterKing ! Pause
            case "start"             ⇒ clusterKing ! Start
            case "stabilize"         ⇒ clusterKing ! Stabilize
            case "printLog"          ⇒ clusterKing ! PrintLog(id = b1i)
            case "get"               ⇒ clusterKing ! Get(clientID = b1i, songName = b2)
            case "delete"            ⇒ clusterKing ! Delete(clientID = b1i, songName = b2)
            case "put"               ⇒ clusterKing ! Put(clientID = b1i, songName = b2, url = brkStr(3))

            case "shutdown" ⇒ clusterKing ! KillEmAll
            case "hello" ⇒ clusterKing ! Hello
            case a ⇒
                err println s"I don't know how to $a"
                handleNext

        }
    }
}

/**
 * Receives command line commands from the CLI and routes them to the appropriate receivers
 */
class Master extends GroupMem {
    override var nodeID = -1

    var members = Map.empty[NodeID, Member]

    /**
     * the client ID that will be assigned to the next Client to join the cluster
     */
    var clientID: NodeID = -1

    /**
     * the server ID that will be assigned to the next Server to join the cluster
     */
    var serverID: NodeID = -1

    var retiredServers = Map.empty[NodeID, Member]

    var stabilizeActor: ActorRef = _
    var stabilizerCounter = 0
    def nextStabilizer() = {
        stabilizerCounter += 1
        s"stabilizer-$stabilizerCounter"
    }

    /** Sign me up to be notified when a member joins or is removed from the macro-cluster */
    val cluster: Cluster = Cluster(context.system)
    override def preStart(): Unit = cluster.subscribe(self, classOf[MemberUp], classOf[MemberRemoved])
    override def postStop(): Unit = cluster unsubscribe self

    def servers: Map[NodeID, Member] = members collect { case (k,v) if v.roles.head == "server" ⇒ k → v }
    def clients: Map[NodeID, Member] = members collect { case (k,v) if v.roles.head == "client" ⇒ k → v }
    def selFromMember(m: Member): ActorSelection = getSelection(getPath(m))

    def getMember(id: NodeID): ActorSelection = selFromMember(members(id))
    def serverPaths: Map[NodeID, ActorPath] = servers map { case (i, mem) ⇒ i → getPath(mem) }

    def broadcastAll(msg: Msg): Msg = broadcast(members.values, msg)
    def broadcastServers(msg: Msg): Msg = broadcast(servers.values, msg)
    def broadcastClients(msg: Msg): Msg = broadcast(clients.values, msg)
    def broadcast(who: Iterable[Member], msg: Msg): Msg = { who foreach { selFromMember(_) ! msg }; msg }
    def killEmAll(): Unit = members.values foreach { selFromMember(_) ! PoisonPill }

    /* TODO use partial function chaining to separate blocking from non-blocking calls
     * and get rid of having "handleNext" all over the place bc that's just confusing
     * and almost definitely incorrect. */
    override def handleMsg: PartialFunction[Msg, Unit] = {

        case NewTracker(sid) ⇒ serverID = sid

        case NewClient(cid, sid) ⇒
            clientID = cid
            serverID = sid

        // a server is fully up and running, so we can unblock
        case IExist(id) ⇒ handleNext

        /** used to terminate blocking */
        case Gotten ⇒ handleNext

        case m @ RetireTracker(id) ⇒
            getMember(id) ! m
            members -= id

        case Hello ⇒
            broadcastAll(Hello)
            handleNext

        // wait for Gotten from Client (should be real quick)
        case m @ PutAndDelete(id) ⇒ getMember(id) forward m

        // wait for Gotten from Server after having printed
        case m @ PrintLog(id) ⇒ getMember(id) forward m

        // wait for Gotten from Client receiving NewVVs from Server
        case m @ Get(id,_) ⇒ getMember(id) forward m

        // Gotten will be sent by node `i`
        case m @ BreakConnection(i, j) ⇒ Seq(i, j) foreach (getMember(_) forward m)

        case m @ RestoreConnection(i, j) ⇒
            if (clients contains i)
                getMember(i) ! ServerPath(j, getPath(members(j)))
            else if (clients contains j)
                getMember(j) ! ServerPath(i, getPath(members(i)))

            // both are servers, Gotten will be sent by node `i`
            else Seq(i, j) foreach { getMember(_) ! RestoreConnection(j, i) }

        case m : BrdcstServers ⇒
            broadcastServers(m)
            handleNext

        case Stabilize ⇒
            broadcastServers(Stabilize)
            stabilizeActor = context.actorOf(Props[StabilizeActor], name = nextStabilizer())
            stabilizeActor ! Hello

        case Updating ⇒
            if (stabilizeActor != null)
                stabilizeActor ! Updating
            else log warning "no stabilize actor available"

        case DoneStabilizing ⇒
            broadcastServers(DoneStabilizing)
            handleNext

    }

    override def receive: PartialFunction[Any, Unit] = handleClusterCallback orElse printReceive

    val handleClusterCallback: PartialFunction[Any, Unit] = {

        /**
         * A member has 'officially' been added to the macro-cluster.
         *
         * We should tell it what it's ID for the command line is, as well as
         *   - For Clients: send them the path to the server it should talk to
         *   - For Servers: send them the paths of all other servers so it can tell them its path
         *   - For Masters: ignore.
         *
         * We still don't `handleNext` until they have received the GangInitiation
         */
        case MemberUp(m) ⇒ integrateNewMember(m)

        /**
         * Member has been 'officially' removed from the macro-cluster.
         * The duration we wait before marking an "unreachable" node as "down"
         * (and therefore removed) is configured by e.g. "auto-down-unreachable-after = 1s"
         */
        case MemberRemoved(m, prevStatus) ⇒ removeMember(m)
    }

    def removeMember(m: Member) { members = members filterNot { case (k, v) ⇒ m == v } }

    /**
     * we `handleNext` immediately for clients and master,
     * but not for servers, who have to wait until an IExist is received
     */
    def integrateNewMember(m: Member) {
        m.roles.head match {
            case "client" ⇒
                val cid: NodeID = clientID
                val sid: NodeID = serverID

                if (cid == -1) { err println "cid hasn't been set"; return }
                if (sid == -1) { err println "sid hasn't been set"; return }
                if (members contains cid) { err println s"Node $cid already exists"; return }
                if (!(members contains sid)) { err println s"Server $sid doesn't exist"; return }

                members += (cid → m) // save reference to this member

                // tell the client the server it is supposed to connect to
                val client = selFromMember(m)
                client ! IDMsg(cid)
                client ! ServerPath(sid, getPath(members(serverID)))

            case "server" ⇒
                val sid: NodeID = serverID

                if (sid == -1) { err println "sid hasn't been set"; return }
                if (members contains sid) { err println s"Node $sid already exists"; return }

                members += (sid → m) // save reference to this member

                val server = selFromMember(m)
                server ! IDMsg(sid)
                server ! CreateServer(serverPaths)

            case "master" ⇒ handleNext
        }
    }
}

/* This stuff is for Stabilize */
class StabilizeActor extends Actor {
    import context._
    case object ThePause
    var hasRcvd = true
    var masterRef: ActorRef = _
    system.scheduler.schedule(1 second, 2 seconds, self, ThePause)
    override def receive = {
        case Hello ⇒ masterRef = sender()
        case Updating ⇒ hasRcvd = true
        case ThePause ⇒
            if (!hasRcvd) {
                masterRef ! DoneStabilizing
                self ! PoisonPill
            }
            else hasRcvd = false
    }
}
