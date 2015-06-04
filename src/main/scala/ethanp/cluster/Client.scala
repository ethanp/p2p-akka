package ethanp.cluster

import akka.actor._
import ethanp.cluster.ClusterUtil.{LCValue, NodeID, ServerName}

/**
 * Ethan Petuchowski
 * 4/9/15
 */
class Client extends GroupMem {

    /* ≤ 1 server connected at a time */
    var server: ActorSelection = _
    var serverID: NodeID = _
    var masterRef: ActorRef = _
    var serverName: ServerName = _
    override var nodeID: NodeID = -4 // "unset"

    /**
     * "To find an acceptable server, the session manager
     *  must check that one or both of these session vectors
     *  are dominated by the server’s version vector."
     */
    var readVec  = new ImmutableVV
    var writeVec = new ImmutableVV

    var currWID: LCValue = 0
    def nextWID(): LCValue = { currWID += 1; currWID }

    override def handleMsg: PartialFunction[Any, Unit] = {

        case IDMsg(id)  ⇒
            masterRef = sender()
            nodeID = id

        /**
         * Client's "Read" command
         * Wait for NewVVs then unblock Master
         */
        case m: Get ⇒ server ! ClientGet(writeVec, readVec, m)

        /**
         * CLI issued "Write" command Put or Delete
         *
         * I am assuming each test-script corresponds to a SINGLE "session"
         *
         * Tell Server with VVs
         * Server will DROP if session props aren't satisfied
         * Server will respond with `NewVVs` (below)
         * Then we acknowledge to Master
         */
        case m: PutAndDelete ⇒
            ClusterUtil.printIf(s"client $nodeID sending $serverID @ $server a write")
            server ! ClientWrite(writeVec, readVec, m)

        /**
         * received from Server after Read or Write for session-guarantee purposes
         */
        case NewVVs(wVec, rVec) ⇒
            writeVec = wVec
            readVec = rVec
            masterRef ! Gotten

        /**
         * Reply from Server for Get request
         * Wait for NewVVs before unblocking Master
         */
        case s @ Song(name, url) ⇒ println(s.str)

        /**
         * This is sent by the master on memberUp(clientMember)
         * and on RestoreConnection
         */
        case ServerPath(id, path) =>
            serverID = id
            server = ClusterUtil getSelection path
            server ! ClientConnected(nodeID)
            // server will respond with its ServerName (see below)

        case m: ServerName ⇒
            serverName = m
            masterRef ! Gotten // master unblocks on CreateClient & RestoreConnection

        /** Current server is retiring, and this is the info for a new one */
        case ServerSelection(id, sel) ⇒
            serverID = id
            server = sel

        /* don't reply with `ClientConnected` because that will
           make the server send an extra Gotten to Master */
//            server ! ClientConnected

        case BreakConnection(a, b) ⇒
            def oneIsMe = a == nodeID || b == nodeID
            def oneIsMyServer = a == serverID || b == serverID
            if (oneIsMe && oneIsMyServer) server = null
            else log error s"client $nodeID rcvd break-conn for non-existent server"
            if (a == nodeID) masterRef ! Gotten

        case Hello ⇒ println(s"client $nodeID connected to server $serverID")

        case KillEmAll ⇒ context.system.shutdown()

        case m ⇒ log error s"client received non-client command: $m"
    }
}

object Client {
    def main(args: Array[String]): Unit = ClusterUtil joinClusterAs "client"
}
