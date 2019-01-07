/*
 * Copyright (C) 2018 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.persistence.couchbase

import akka.{Done, NotUsed}
import akka.annotation.InternalApi
import akka.dispatch.ExecutionContexts
import akka.event.Logging
import akka.persistence.couchbase.internal.CouchbaseSchema.{MessageForWrite, Queries, TaggedMessageForWrite}
import akka.persistence.couchbase.internal._
import akka.persistence.journal.{AsyncWriteJournal, Tagged}
import akka.persistence.{AtomicWrite, PersistentRepr}
import akka.serialization.{Serialization, SerializationExtension}
import akka.stream.ActorMaterializer
import akka.stream.alpakka.couchbase.CouchbaseSessionRegistry
import akka.stream.alpakka.couchbase.scaladsl.CouchbaseSession
import akka.stream.scaladsl.{Sink, Source}
import com.couchbase.client.java.document.JsonDocument
import com.couchbase.client.java.query._
import com.couchbase.client.java.query.consistency.ScanConsistency
import com.typesafe.config.Config

import scala.collection.{immutable => im}
import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

/**
 * INTERNAL API
 */
@InternalApi
private[akka] object CouchbaseJournal {

  final case class PersistentActorTerminated(persistenceId: String)

  private final case class WriteFinished(persistenceId: String, f: Future[Done])

  private val ExtraSuccessFulUnit: Try[Unit] = Success(())

}

/**
 * INTERNAL API
 */
@InternalApi
class CouchbaseJournal(config: Config, configPath: String)
    extends AsyncWriteJournal
    with AsyncCouchbaseSession
    with Queries
    with TagSequenceNumbering {

  import CouchbaseJournal._
  import akka.persistence.couchbase.internal.CouchbaseSchema.Fields

  protected val log = Logging(this)
  protected implicit val system = context.system
  protected implicit val executionContext: ExecutionContext = context.dispatcher

  private val serialization: Serialization = SerializationExtension(system)
  private implicit val materializer = ActorMaterializer()(context)
  private val uuidGenerator = UUIDGenerator()

  // Without this the index that gets the latest sequence nr may have not seen the last write of the last version
  // of this persistenceId. This seems overkill.
  protected val queryConsistency = N1qlParams.build().consistency(ScanConsistency.REQUEST_PLUS)

  private val settings: CouchbaseJournalSettings = {
    // shared config is one level above the journal specific
    val commonPath = configPath.replaceAll("""\.write$""", "")
    val sharedConfig = context.system.settings.config.getConfig(commonPath)

    CouchbaseJournalSettings(sharedConfig)
  }
  private val n1qlQueryStageSettings = N1qlQueryStage.N1qlQuerySettings(
    Duration.Zero, // We don't do live queries
    settings.replayPageSize
  )
  def bucketName: String = settings.bucket

  // if there are pending writes when an actor restarts we must wait for
  // them to complete before we can read the highest sequence number or we will miss it
  private val writesInProgress = new java.util.HashMap[String, Future[Done]]()

  protected val asyncSession: Future[CouchbaseSession] =
    CouchbaseSessionRegistry(system).sessionFor(settings.sessionSettings, settings.bucket)
  asyncSession.failed.foreach { ex =>
    log.error(ex, "Failed to connect to couchbase")
    context.stop(self)
  }

  if (settings.warnAboutMissingIndexes) {
    for {
      session <- asyncSession
      indexes <- session.listIndexes().runWith(Sink.seq)
    } {
      val indexNames = indexes.map(_.name()).toSet
      Set("persistence-ids", "sequence-nrs").foreach { requiredIndex =>
        if (!indexNames(requiredIndex))
          log.error(
            "Missing the [{}] index, the journal will not work without it, se plugin documentation for details",
            requiredIndex
          )
      }
    }
  }

  override def receivePluginInternal: Receive = {
    case PersistentActorTerminated(pid) =>
      log.debug("Persistent actor [{}] stopped, flushing tag-seq-nrs", sender().path)
      evictSeqNrsFor(pid)
    case WriteFinished(pid, f) =>
      writesInProgress.remove(pid, f)
  }

  override def asyncWriteMessages(messages: im.Seq[AtomicWrite]): Future[im.Seq[Try[Unit]]] = {
    log.debug("asyncWriteMessages {}", messages)
    require(messages.nonEmpty)
    // Note that we assume that all messages have the same persistenceId, which is
    // the case for Akka 2.4.2.
    val pid = messages.head.persistenceId
    val writeCompleted = Promise[Done]()
    if (writesInProgress.put(pid, writeCompleted.future) ne null)
      throw new IllegalStateException(s"Got write for pid $pid before previous write completed")

    val writesCompleted = messages.map { write =>
      val writeResult = atomicWriteToJsonDoc(write).flatMap(insertJsonDoc)
      writeResult
    }

    val sequencedWrites = Future.sequence(writesCompleted)

    sequencedWrites.onComplete {
      case _ =>
        self ! WriteFinished(pid, writeCompleted.future)
        writeCompleted.success(Done)
    }

    sequencedWrites
  }

  private def insertJsonDoc(jsonDoc: JsonDocument): Future[Try[Unit]] =
    withCouchbaseSession { session =>
      session
        .insert(jsonDoc, settings.writeSettings)
        .map(json => ExtraSuccessFulUnit)(ExecutionContexts.sameThreadExecutionContext)
        .recover {
          // lift the failure to not fail the whole sequence
          case NonFatal(ex) => Failure(ex)
        }(ExecutionContexts.sameThreadExecutionContext)
    }

  private def atomicWriteToJsonDoc(write: AtomicWrite): Future[JsonDocument] = {
    // Needs to be sequential because of tagged event sequence numbering
    val messagesForWrite: Future[im.Seq[MessageForWrite]] =
      FutureUtils.traverseSequential(write.payload) { persistentRepr =>
        persistentRepr.payload match {
          case t: Tagged =>
            val serializedF = SerializedMessage.serialize(serialization, t.payload.asInstanceOf[AnyRef])
            val tagsAndSeqNrsF = FutureUtils.traverseSequential(t.tags.toList)(
              tag => nextTagSeqNrFor(persistentRepr.persistenceId, tag).map(n => tag -> n)
            )
            for {
              serialized <- serializedF
              tagsAndSeqNrs <- tagsAndSeqNrsF
            } yield
              new TaggedMessageForWrite(persistentRepr.sequenceNr, serialized, uuidGenerator.nextUuid(), tagsAndSeqNrs)

          case other =>
            SerializedMessage
              .serialize(serialization, other.asInstanceOf[AnyRef])
              .map(serialized => new MessageForWrite(persistentRepr.sequenceNr, serialized))
        }
      }

    messagesForWrite.map { messages =>
      CouchbaseSchema.atomicWriteAsJsonDoc(
        write.persistenceId,
        write.payload.head.writerUuid.toString,
        messages,
        write.lowestSequenceNr
      )
    }(ExecutionContexts.sameThreadExecutionContext)
  }

  override def asyncDeleteMessagesTo(persistenceId: String, toSequenceNr: Long): Future[Unit] =
    withCouchbaseSession { session =>
      log.debug("asyncDeleteMessagesTo({}, {})", persistenceId, toSequenceNr)
      val newMetadataEntry: Future[JsonDocument] =
        if (toSequenceNr == Long.MaxValue) {
          log.debug("Journal cleanup (Long.MaxValue)")
          asyncReadHighestSequenceNr(persistenceId, 0).map { highestSeqNr =>
            CouchbaseSchema.metadataEntry(persistenceId, highestSeqNr)
          }
        } else Future.successful(CouchbaseSchema.metadataEntry(persistenceId, toSequenceNr))

      newMetadataEntry
        .flatMap(entry => session.upsert(entry, settings.writeSettings))
        .map(_ => ())(ExecutionContexts.sameThreadExecutionContext)
    }

  override def asyncReplayMessages(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long, max: Long)(
      recoveryCallback: PersistentRepr => Unit
  ): Future[Unit] =
    withCouchbaseSession { session =>
      log.debug("asyncReplayMessages({}, {}, {}, {})", persistenceId, fromSequenceNr, toSequenceNr, max)

      val deletedTo: Future[Long] = firstNonDeletedEventFor(persistenceId, session, settings.readTimeout)
        .map(_.getOrElse(fromSequenceNr))

      val replayFinished: Future[Unit] = deletedTo.flatMap { firstNonDeletedSeqNr =>
        // important to not start at 0 since that would skew the paging
        val startOfFirstPage = math.max(1, firstNonDeletedSeqNr)
        val endOfFirstPage =
          math.min(startOfFirstPage + settings.replayPageSize - 1, toSequenceNr)

        val firstQuery = replayQuery(persistenceId, startOfFirstPage, endOfFirstPage, queryConsistency)

        log.debug("Starting at sequence_nr {}, query: {}", startOfFirstPage, firstQuery)
        val source: Source[AsyncN1qlQueryRow, NotUsed] = Source.fromGraph(
          new N1qlQueryStage[Long](
            false,
            n1qlQueryStageSettings,
            firstQuery,
            session.underlying,
            endOfFirstPage, { endOfPreviousPage =>
              val startOfNextPage = endOfPreviousPage + 1
              if (startOfNextPage > toSequenceNr) {
                None
              } else {
                val endOfNextPage = math.min(startOfNextPage + settings.replayPageSize - 1, toSequenceNr)
                Some(replayQuery(persistenceId, startOfNextPage, endOfNextPage, queryConsistency))
              }
            },
            (endOfPage, row) => row.value().getLong(Fields.SequenceNr)
          )
        )

        val complete = source
          .take(max)
          .mapAsync(1)(row => CouchbaseSchema.deserializeEvent(row.value(), serialization))
          .runForeach { pr =>
            recoveryCallback(pr)
          }
          .map(_ => ())(ExecutionContexts.sameThreadExecutionContext)

        complete.onComplete {
          // For debugging while developing
          case Failure(ex) => log.error(ex, "Replay error for [{}]", persistenceId)
          case _ =>
            log.debug("Replay completed for {}", persistenceId)
        }

        complete
      }

      replayFinished
    }

  override def asyncReadHighestSequenceNr(persistenceId: String, fromSequenceNr: Long): Future[Long] = {
    // watch the persistent actor so that we can flush tag-seq-nrs for it when it stops
    // until we have akka/akka#25970 this is the place to do that
    if (sender != system.deadLetters)
      context.watchWith(sender(), PersistentActorTerminated(persistenceId))
    val pendingWrite = Option(writesInProgress.get(persistenceId)) match {
      case Some(f) =>
        log.debug("Write in progress for {}, deferring highest seq nr until write completed", persistenceId)
        f
      case None => Future.successful(Done)
    }
    pendingWrite.flatMap(
      _ =>
        withCouchbaseSession { session =>
          log.debug("asyncReadHighestSequenceNr({}, {})", persistenceId, fromSequenceNr)

          val query = highestSequenceNrQuery(persistenceId, fromSequenceNr, queryConsistency)
          log.debug("Executing: {}", query)

          session
            .singleResponseQuery(query)
            .map {
              case Some(jsonObj) =>
                log.debug("highest sequence nr for {}: {}", persistenceId, jsonObj)
                if (jsonObj.get("max") != null) jsonObj.getLong("max")
                else 0L
              case None => // should never happen
                0L
            }
      }
    )
  }
}
