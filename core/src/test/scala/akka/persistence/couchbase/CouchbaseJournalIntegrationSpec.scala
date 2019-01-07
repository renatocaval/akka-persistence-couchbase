/*
 * Copyright (C) 2018 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.persistence.couchbase

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKit, WithLogCapturing}
import com.typesafe.config.ConfigFactory
import org.scalatest.{Matchers, WordSpecLike}

import scala.concurrent.duration._

class CouchbaseJournalIntegrationSpec
    extends TestKit(
      ActorSystem(
        "CouchbaseJournalIntegrationSpec",
        ConfigFactory.parseString("""
        akka.loglevel = debug
        akka.loggers = ["akka.testkit.SilenceAllTestEventListener"]
      """).withFallback(ConfigFactory.load())
      )
    )
    with ImplicitSender
    with WordSpecLike
    with Matchers
    with CouchbaseBucketSetup
    with WithLogCapturing {

  "The Couchbase Journal" must {

    "always replay to the latest written event" in {
      // even with outstanding writes - covers #140, and also that replay of a longer journal works
      val ref1 = system.actorOf(TestActor.props("latest-written"))

      ref1 ! TestActor.PersistAllAsync((0 to 500).map(_.toString))
      expectMsg("PersistAllAsync-triggered") // large write to be in progress when replay happens _ =>

      watch(ref1)
      ref1 ! TestActor.Stop
      expectTerminated(ref1)

      // if write is still happening, recovery finding highest seqnr should still work
      val ref2 = system.actorOf(TestActor.props("latest-written"))
      ref2 ! TestActor.GetLastRecoveredEvent
      expectMsg(10.seconds, "500")
    }

  }

}
