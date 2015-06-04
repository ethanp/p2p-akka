package ethanp.cluster

import akka.actor.{ActorSelection, ActorPath}
import ethanp.cluster.ClusterUtil._

import scala.collection.{SortedSet, immutable, mutable}

/**
 * Ethan Petuchowski
 * 4/9/15
 */
sealed class Msg() extends Serializable
sealed trait MasterMsg extends Msg
sealed trait Action extends Msg { def str: Option[String] }

class NetworkPartition(val i: NodeID, val j: NodeID) extends MasterMsg
object NetworkPartition { def unapply(fwd: NetworkPartition): Option[(NodeID, NodeID)] = Some(fwd.i, fwd.j) }

trait BrdcstServers extends MasterMsg
case class  RetireServer(id: NodeID) extends Msg
case class  BreakConnection(id1: NodeID, id2: NodeID)   extends NetworkPartition(id1, id2)
case class  RestoreConnection(id1: NodeID, id2: NodeID) extends NetworkPartition(id1, id2)
case class  IDMsg(id: NodeID) extends Msg
case class  PrintLog(id: NodeID) extends Msg

abstract class PutAndDelete(val cliID: NodeID) extends Action
object PutAndDelete { def unapply(pad: PutAndDelete): Option[NodeID] = Some(pad.cliID) }
case class  Put(clientID: NodeID, songName: String, url: String) extends PutAndDelete(clientID) {
    override def str: Option[String] = Some(s"PUT:($songName, $url):")
}
case class Delete(clientID: NodeID, songName: String) extends PutAndDelete(clientID) {
    override def str: Option[String] = Some(s"DELETE:($songName):")
}

case class ClientWrite(writeVec: ImmutableVV, readVec: ImmutableVV, writeReq: PutAndDelete) extends Msg
case class ClientGet(writeVec: ImmutableVV, readVec: ImmutableVV, getReq: Get) extends Msg

case class NewVVs(writeVec: ImmutableVV, readVec: ImmutableVV) extends Msg

case class Get(clientID: NodeID, songName: String) extends Msg
case class Song(songName: String, url: URL) extends Msg {
    def str: String = s"$songName:$url"
}
case class Retirement(serverName: ServerName) extends Action {
    override def str: Option[String] = None
}

case object Pause     extends BrdcstServers
case object Start     extends BrdcstServers
case object Stabilize extends Msg

case class  ServerPath(id: NodeID, path: ActorPath) extends Msg
case class  ServerSelection(id: NodeID, sel: ActorSelection) extends Msg
case class  CreateServer(servers: Map[NodeID, ActorPath]) extends Msg
case class  ClientConnected(id: NodeID) extends Msg
case class  IExist(nodeID: NodeID)      extends Msg

case class Write(commitStamp: LCValue, acceptStamp: AcceptStamp, action: Action) extends Ordered[Write] {
    override def compare(that: Write): Int =
        if (commitStamp != that.commitStamp) commitStamp - that.commitStamp
        else acceptStamp compare that.acceptStamp

    /* 'OP_TYPE:(OP_VALUE):STABLE_BOOL' */
    def strOpt = action.str map { _ + { if (isCommitted) "TRUE" else "FALSE" } }
    def commit(stamp: LCValue) = Write(stamp, acceptStamp, action)
    def isCommitted = commitStamp < INF
    def tentative = commitStamp == INF
}
object Write {
    def apply(action: Action) = Write
}
case class AcceptStamp(acceptor: ServerName, acceptVal: LCValue) extends Msg with Ordered[AcceptStamp] {
    override def compare(that: AcceptStamp): Int = {
        if (acceptVal != that.acceptVal) {
            acceptVal compare that.acceptVal
        }
        else {
            if (acceptor == null && that.acceptor == null) 0
            else if (acceptor == null) -1
            else if (that.acceptor == null) 1
            else acceptor compare that.acceptor
        }
    }
}

sealed trait AntiEntropyMsg extends Msg
case object LemmeUpgradeU extends AntiEntropyMsg
case class UpdateWrites(writes: SortedSet[Write]) extends AntiEntropyMsg
case class CurrentKnowledge(versionVector: ImmutableVV, csn: LCValue) extends AntiEntropyMsg
case object Hello extends Msg
case class NewClient(cid: NodeID, sid: NodeID)  extends Msg
case class NewTracker(sid: NodeID) extends Msg
case object CreationWrite extends Msg with Action { override def str: Option[String] = None } // don't print

/**
 * For ease of use within servers, I'd like to have a _mutable_ version vector,
 * But for doing message passing, I don't want to pass a mutable object bc I've heard that's
 *  "bad" and I don't know enough about it to be able to evaluate it for this case, so I'm
 *  just going to stay away from doing that, so I have an _immutable_ version that can be
 *  created out of the mutable one and vice-versa.
 */
sealed trait VersionVector extends Ordered[VersionVector] {
    override def compare(that: VersionVector): Int = ??? // I know this one, just haven't needed it
    def get(serverName: ServerName): LCValue = vectorMap(serverName)
    val vectorMap: scala.collection.Map[ServerName, LCValue]
    def knowsAbout(name: ServerName) = vectorMap contains name

    def isOlderThan(ts: AcceptStamp): Boolean = {
        val AcceptStamp(acc, v) = ts
        /**
         * From (Lec 11, pg. 6)
         *    - If `vec(R_k) ≥ TS_{k,j}`, don't forward writes ACCEPTED by R_j
         *    - Else send R_i all writes accepted by R_j
         *
         * Not entirely sure this implementation is correct, I just like how clean it is.
         */
        def knownAndNewer = knowsAbout(acc) && (vectorMap(acc) < v)
        def unknownAndNewer = acc != null && (this isOlderThan acc)
        knownAndNewer || unknownAndNewer
    }

    def isSince(acc: AcceptStamp) = !isOlderThan(acc)

    def apply(name: ServerName): LCValue = vectorMap(name)
    override def toString: String = vectorMap.toString()
    def size = vectorMap.size
}

case class ImmutableVV(vectorMap: immutable.Map[ServerName, LCValue] = immutable.Map.empty) extends VersionVector {
    def update(name: ServerName, lcVal: LCValue): ImmutableVV = {
        var newMap: Map[ServerName, LCValue] = vectorMap.toSeq.toMap // I *think* this oughtta (shallow) COPY that map
        newMap += (name → lcVal)
        ImmutableVV(newMap)
    }
}

class MutableVV(val vectorMap: mutable.Map[ServerName, LCValue] = mutable.Map.empty) extends VersionVector {

    def dominates(v: VersionVector): Boolean = v.vectorMap.forall { case (sn, lc) ⇒
            val since: Boolean = isSince(AcceptStamp(sn, lc))
            if (!since) printIf(s"ERR_DEP because $vectorMap is missing $sn or is < $lc")
            since
    }

    def increment(name: ServerName): LCValue = {
        vectorMap(name) += 1
        vectorMap(name)
    }

    def addNewMember(tup: (ServerName, LCValue)): Unit = vectorMap(tup._1) = tup._2

    def updateWith(writes: SortedSet[Write]): Unit = writes foreach update

    /**
     * add any created members to the VV
     * remove any retired members from the VV
     * update the VV for members with newer writes
     *
     * "R.V(X), is the largest accept-stamp of any write known to R
     *  that was originally accepted from a client by X." (pg. 2 aka 289)
     */
    def update(write: Write): Unit = {
        val stamp = write.acceptStamp
        val acceptor = stamp.acceptor
        val writeVal = stamp.acceptVal

        def updateIfNewer(): Unit =
            if (this isOlderThan stamp)
                vectorMap(acceptor) = writeVal

        write.action match {
            case m: Put        ⇒ updateIfNewer()
            case m: Delete     ⇒ updateIfNewer()
            case m: Retirement ⇒ vectorMap remove acceptor // not entirely positive about this
            case CreationWrite ⇒ vectorMap(stamp) = writeVal
        }
    }
}

object ImmutableVV {
    def apply(mut: MutableVV): ImmutableVV = ImmutableVV(mut.vectorMap.toMap)
}

object MutableVV {
    def apply(map: Map[ServerName, LCValue]): MutableVV = new MutableVV(mutable.Map() ++ map)
    def apply(elems: (ServerName, LCValue)*): MutableVV = MutableVV(elems.toMap)
    def apply(imm: ImmutableVV): MutableVV = MutableVV(imm.vectorMap)
}

class MutableDB(val state: mutable.Map[String, URL] = mutable.Map.empty) {
    def update(action: Action) {
        ??? // TODO (at some point?)
    }
}

case class GangInitiation(
         yourName: ServerName,
         writes  : immutable.SortedSet[Write],
         csn     : LCValue,
         vsnVec  : ImmutableVV) extends Msg

case object Updating extends Msg
case object DoneStabilizing extends Msg
case object Gotten extends Msg
case object URPrimary extends Msg
case object KillEmAll extends Msg
