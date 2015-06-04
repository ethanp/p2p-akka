package ethanp.cluster.givenTests

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKit}
import ethanp.cluster.Master
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

/**
 * Ethan Petuchowski
 * 4/13/15
 */
//noinspection EmptyParenMethodOverridenAsParameterless
class Test1_1(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
  with WordSpecLike with Matchers with BeforeAndAfterAll {

  def this() = this(ActorSystem("ClusterSystem"))

  /* Example of what I can do in a test setup like this */
//      val echo = system.actorOf(Props[Master])
//      echo ! "hello world"
//      expectMsg("hello world")

  override def afterAll { TestKit shutdownActorSystem system }

  "An master " must {
    "send back messages unchanged" in {

      Master main Array.empty

      Master handle "joinServer 0"
      Master handle "joinServer 1"
      Master handle "joinClient 2 0"
      Master handle "joinClient 3 1"
      Master handle "put 2 eyesOfTexas utexas.edu"
      Master handle "shutdown"

//      Master handle "stabilize"
//      Master handle "get 3 eyesOfTexas"



//      within (200 milli) {
//
//      }
    }

  }
}
