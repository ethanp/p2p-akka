package ethanp.cluster

import akka.actor._
import akka.cluster.Member
import com.typesafe.config.ConfigFactory
import ethanp.cluster.ClusterUtil.{logging, NodeID}

/**
 * Ethan Petuchowski
 * 4/10/15
 */
object ClusterUtil {

    type NodeID = Int
    type LCValue = Int
    type URL = String
    type ServerName = AcceptStamp

    val INF: LCValue = Integer.MAX_VALUE

    val logging = true
    def printIf(x: Any) { if (logging) println(x) }

    var systems = List.empty[ActorSystem]

    def joinClusterAs(role: String): ActorRef = ClusterUtil.joinClusterAs("0", role)

    def joinClusterAs(port: String, role: String): ActorRef = {
        val config = ConfigFactory.parseString(s"akka.remote.netty.tcp.port=$port").
                withFallback(ConfigFactory.parseString(s"akka.cluster.roles = [$role]")).
                withFallback(ConfigFactory.load())
        val system = ActorSystem("ClusterSystem", config)
        systems ::= system
        role match {
            case "server" ⇒ system.actorOf(Props[Tracker], name = "server")
            case "client" ⇒ system.actorOf(Props[Client], name = "client")
            case "master" ⇒ system.actorOf(Props[Master], name = "master")
        }
    }

    /**
     * Gets the (Protocol, IP-Address, Port) information for a node in the cluster.
     */
    def getPath(m: Member): ActorPath = RootActorPath(m.address) / "user" / m.roles.head

    /**
     * Gets a reference to an object that will create socket to a node in the cluster
     * on demand when a message needs to be sent. (I think.)
     */
    def getSelection(path: ActorPath)(implicit context: ActorContext): ActorSelection = context actorSelection path
}

trait GroupMem extends Actor with ActorLogging {
    var nodeID: NodeID

    /**
     * `logging` can be set in the ClusterUtil above
     */
    def logMsg(x: Any) { if (logging) println(x) }
    val printMsg: PartialFunction[Any, Msg] = {
        case any: Msg ⇒
            logMsg(s"node $nodeID rcvd: $any")
            any
    }
    def handleMsg: PartialFunction[Msg, Unit]
    val printReceive: PartialFunction[Any, Unit] = printMsg andThen handleMsg
    override def receive: PartialFunction[Any, Unit] = printReceive
}
