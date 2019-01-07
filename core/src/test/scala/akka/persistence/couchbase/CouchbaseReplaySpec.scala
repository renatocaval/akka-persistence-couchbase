/*
 * Copyright (C) 2018 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.persistence.couchbase

import akka.actor.PoisonPill
import akka.persistence.couchbase.scaladsl.AbstractCouchbaseSpec
import akka.testkit.EventFilter
import com.couchbase.client.java.document.JsonDocument
import com.typesafe.config.ConfigFactory

class CouchbaseReplaySpec
    extends AbstractCouchbaseSpec("CouchbaseReplaySpec",
                                  ConfigFactory.parseString("""
 akka.loggers = [akka.testkit.TestEventListener]
  """.stripMargin))
    with CouchbaseBucketSetup {

  "Replay" must {
    "fail if next document found" in new Setup {
      override def initialPersistedEvents: Int = 2
      // pid-1 and pid-2 are used as the first two document ids
      probe.watch(persistentActor)
      persistentActor ! PoisonPill
      probe.expectTerminated(persistentActor)

      couchbaseSession.insert(JsonDocument.create(s"$pid-3")).futureValue

      EventFilter[RuntimeException](message = "Read highest sequence nr 2 but found document with id 1-3",
                                    occurrences = 1).intercept {
        system.actorOf(TestActor.props(pid))
      }
    }
  }

}
