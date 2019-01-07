/*
 * Copyright (C) 2018 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.persistence.couchbase.internal

import java.util.{Base64, UUID}

import akka.actor.{ActorSystem, ExtendedActorSystem}
import akka.annotation.InternalApi
import akka.dispatch.ExecutionContexts
import akka.persistence.couchbase._
import akka.persistence.{PersistentRepr, SnapshotMetadata}
import akka.serialization.{AsyncSerializer, Serialization, Serializers}
import akka.stream.alpakka.couchbase.scaladsl.CouchbaseSession
import akka.util.OptionVal
import com.couchbase.client.java.document.JsonDocument
import com.couchbase.client.java.document.json.{JsonArray, JsonObject}
import com.couchbase.client.java.query.{N1qlParams, N1qlQuery}

import scala.collection.JavaConverters._
import scala.collection.{immutable => im}
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
import scala.util.control.NonFatal

/*
 * INTERNAL API
 */
@InternalApi
private[akka] final object CouchbaseSchema {

  sealed class MessageForWrite(val sequenceNr: Long, val msg: SerializedMessage)
  final class TaggedMessageForWrite(sequenceNr: Long,
                                    msg: SerializedMessage,
                                    val ordering: UUID,
                                    val tagsWithSeqNrs: im.Seq[(String, Long)])
      extends MessageForWrite(sequenceNr, msg)

  final case class TaggedPersistentRepr(pr: PersistentRepr,
                                        tags: Set[String],
                                        tagSequenceNumbers: Map[String, Long],
                                        offset: UUID)

  object Fields {
    val Type = "type"
    // for journal, metadata and snapshot document structure samples, see docs

    // journal fields

    // === per doc ===
    val PersistenceId = "persistence_id"
    val WriterUuid = "writer_uuid"
    val Messages = "messages"
    val TagSeqNrs = "tag_seq_nrs"
    // per tag
    val Tag = "tag"
    val TagSeqNr = "seq_nr"

    // === per message/event ===
    val SequenceNr = "sequence_nr"
    // A field to sort in a globally consistent way on, for events by tags, based on the UUID
    val Ordering = "ordering"
    // separate fields for json and base64 bin payloads
    val JsonPayload = "payload"
    val BinaryPayload = "payload_bin"
    // the specific tags on an individual message
    val Tags = "tags"

    // metadata object fields
    val DeletedTo = "deleted_to"

    val SerializerManifest = "ser_manifest"
    val SerializerId = "ser_id"
    val Timestamp = "timestamp"

    // === per persistence-id-entry metadata ===
    val MaxSequenceNr = "max_sequence_nr"
  }

  val MetadataEntryType = "journal_metadata"
  val JournalEntryType = "journal_message"
  val SnapshotEntryType = "snapshot"

  trait Queries {
    protected def bucketName: String

    // TODO how horrific is this query, it does hit the index but it still needs to look at all results?
    // seems to be at worst as fast as the previous ORDER BY + LIMIT 1 query at least
    private lazy val highestSequenceNrStatement =
      s"""
         SELECT MAX(m.sequence_nr) AS max
         FROM ${bucketName} a USE INDEX (`persistence-ids`, `sequence-nrs`)
         UNNEST messages AS m
         WHERE a.type = "${CouchbaseSchema.JournalEntryType}"
         AND a.persistence_id = $$pid
         AND m.sequence_nr >= $$from
      """

    protected def highestSequenceNrQuery(persistenceId: String, fromSequenceNr: Long, params: N1qlParams): N1qlQuery =
      N1qlQuery.parameterized(
        highestSequenceNrStatement,
        JsonObject
          .create()
          .put("pid", persistenceId)
          .put("from", fromSequenceNr: java.lang.Long),
        params
      )

    private lazy val replayStatement =
      s"""
         SELECT a.persistence_id, a.writer_uuid, m.*
         FROM ${bucketName} a USE INDEX (`persistence-ids`, `sequence-nrs`)
         UNNEST messages AS m
         WHERE a.type = "${CouchbaseSchema.JournalEntryType}"
         AND a.persistence_id = $$pid
         AND m.sequence_nr >= $$from
         AND m.sequence_nr <= $$to
         ORDER BY m.sequence_nr
    """

    protected def replayQuery(persistenceId: String, from: Long, to: Long, params: N1qlParams): N1qlQuery =
      N1qlQuery.parameterized(replayStatement,
                              JsonObject
                                .create()
                                .put("pid", persistenceId)
                                .put("from", from)
                                .put("to", to),
                              params)

    /*
     * The rationale behind this query is that indexed queries on
     * arrays that aren't covering don't push down the limit and ordering
     * meaning that every single tagged event is returned and then filtered
     * meaning that it won't scale past a few thousand events.
     * This query just gets the document id and ordering which means it is fully
     * covered by the index meaning that the index does handle the ordering and limit.
     *
     * This is a known limit in Couchbase https://forums.couchbase.com/t/problems-getting-unnest-query-to-use-nested-array-index/19556/4
     * and is not a high priority to fix so this query is used to get the ids then the key value
     * api is used to get the actual events.
     *
     * The ordering comes back as null if m.ordering is used :-/
     *
     */

    private lazy val eventsByTagDocIds =
      s"""
         SELECT meta(a).id, [t, m.ordering][1] as ordering
         FROM ${bucketName} a USE INDEX (tags)
         UNNEST messages m
         UNNEST m.tags t
         WHERE a.type = "${CouchbaseSchema.JournalEntryType}"
         AND [t, m.ordering] >= [$$tag, SUCCESSOR($$fromOffset)]
         AND [t, m.ordering] <= [$$tag, $$toOffset]
         ORDER BY [t, m.ordering]
         LIMIT $$limit
       """

    protected def eventsByTagQueryIds(tag: String, fromOffset: String, toOffset: String, pageSize: Int): N1qlQuery =
      N1qlQuery.parameterized(
        eventsByTagDocIds,
        JsonObject
          .create()
          .put("tag", tag)
          .put("ordering", fromOffset)
          .put("limit", pageSize)
          .put("fromOffset", fromOffset)
          .put("toOffset", toOffset)
      )

    /* Flattens the doc.messages (which can contain batched writes)
     * into elements in the result and adds a field for the persistence id from
     * the surrounding document. Note that the UNNEST name (m) must match the name used
     * for the array value in the index or the index will not be used for these
     */
    private lazy val eventsByPersistenceId =
      s"""
         SELECT a.persistence_id, m.*
         FROM ${bucketName} a USE INDEX (`persistence-ids`, `sequence-nrs`)
         UNNEST messages AS m
         WHERE a.type = "${CouchbaseSchema.JournalEntryType}"
         AND a.persistence_id = $$pid
         AND m.sequence_nr  >= $$from
         AND m.sequence_nr <= $$to
         ORDER by m.sequence_nr
         LIMIT $$limit
    """.stripMargin

    protected def eventsByPersistenceIdQuery(persistenceId: String,
                                             fromSequenceNr: Long,
                                             toSequenceNr: Long,
                                             pageSize: Int): N1qlQuery =
      N1qlQuery.parameterized(eventsByPersistenceId,
                              JsonObject
                                .create()
                                .put("pid", persistenceId)
                                .put("from", fromSequenceNr)
                                .put("to", toSequenceNr)
                                .put("limit", pageSize))

    // IS NOT NULL is needed to hit the index
    private lazy val persistenceIds =
      s"""
         SELECT DISTINCT(persistence_id)
         FROM ${bucketName} USE INDEX (`persistence-ids`)
         WHERE type = "${CouchbaseSchema.JournalEntryType}"
         AND persistence_id IS NOT NULL
     """

    protected def persistenceIdsQuery(): N1qlQuery =
      N1qlQuery.simple(persistenceIds)

    protected def firstNonDeletedEventFor(
        persistenceId: String,
        session: CouchbaseSession,
        readTimeout: FiniteDuration
    )(implicit ec: ExecutionContext): Future[Option[Long]] =
      session
        .get(metadataIdFor(persistenceId), readTimeout)
        .map(_.map { jsonDoc =>
          val dt = jsonDoc.content().getLong(Fields.DeletedTo).toLong
          dt + 1 // start at the next sequence nr
        })
        .recover {
          case NonFatal(ex) =>
            throw new RuntimeException(s"Failed looking up deleted messages for [$persistenceId]", ex)
        }

    private lazy val highestTagSeqNr =
      s"""
        SELECT t.seq_nr FROM ${bucketName} a
        UNNEST messages AS m UNNEST m.tag_seq_nrs AS t
        WHERE a.type = "${CouchbaseSchema.JournalEntryType}"
        AND a.persistence_id = $$pid
        AND t.tag = $$tag
        ORDER BY m.sequence_nr DESC
        LIMIT 1
      """

    def highestTagSequenceNumberQuery(persistenceId: String, tag: String, params: N1qlParams): N1qlQuery =
      N1qlQuery.parameterized(highestTagSeqNr,
                              JsonObject
                                .create()
                                .put("pid", persistenceId)
                                .put("tag", tag),
                              params)

  }

  def snapshotIdFor(metadata: SnapshotMetadata): String = s"${metadata.persistenceId}-${metadata.sequenceNr}-snapshot"

  def documentId(pid: String, lowestSequenceNr: Long): String = s"$pid-$lowestSequenceNr"

  def metadataIdFor(persistenceId: String): String = s"$persistenceId-meta"

  def metadataEntry(persistenceId: String, deletedTo: Long): JsonDocument =
    JsonDocument.create(
      metadataIdFor(persistenceId),
      JsonObject
        .create()
        .put(Fields.Type, CouchbaseSchema.JournalEntryType)
        .put(Fields.DeletedTo, deletedTo)
    )

  // the basic form, shared by snapshots and events
  private def serializedMessageAsJson(msg: SerializedMessage): JsonObject = {
    val json = JsonObject
      .create()
      .put(Fields.SerializerManifest, msg.manifest)
      .put(Fields.SerializerId, msg.identifier)

    msg.nativePayload match {
      case OptionVal.None =>
        json.put(Fields.BinaryPayload, Base64.getEncoder.encodeToString(msg.payload))

      case OptionVal.Some(jsonPayload) =>
        json.put(Fields.JsonPayload, jsonPayload)
    }
    json
  }

  def snapshotAsJsonDoc(msg: SerializedMessage, metadata: SnapshotMetadata): JsonDocument = {
    val json = serializedMessageAsJson(msg)
      .put(Fields.Type, CouchbaseSchema.SnapshotEntryType)
      .put(Fields.Timestamp, metadata.timestamp)
      .put(Fields.SequenceNr, metadata.sequenceNr)
      .put(Fields.PersistenceId, metadata.persistenceId)
    JsonDocument.create(CouchbaseSchema.snapshotIdFor(metadata), json)
  }

  def serializedMessageAsJson(messageForWrite: MessageForWrite): JsonObject = {
    val json = serializedMessageAsJson(messageForWrite.msg)
      .put(Fields.SequenceNr, messageForWrite.sequenceNr)

    messageForWrite match {
      case tagged: TaggedMessageForWrite =>
        json
          .put(Fields.SequenceNr, tagged.sequenceNr)
          .put(Fields.Ordering, TimeBasedUUIDSerialization.toSortableString(tagged.ordering))
          // for the index an array with the tags straight up
          .put(Fields.Tags, JsonArray.from(tagged.tagsWithSeqNrs.map { case (tag, _) => tag }.asJava))
          // for the gap detection, tag and sequence number per tag
          .put(
            Fields.TagSeqNrs,
            JsonArray.from(
              tagged.tagsWithSeqNrs.map {
                case (tag, tagSeqNr) =>
                  JsonObject
                    .create()
                    .put(Fields.Tag, tag)
                    .put(Fields.TagSeqNr, tagSeqNr)
              }.asJava
            )
          )
      case untagged => json
    }
  }

  def atomicWriteAsJsonDoc(pid: String,
                           writerUuid: Any,
                           messages: im.Seq[MessageForWrite],
                           lowestSequenceNr: Long): JsonDocument = {
    val insert: JsonObject = JsonObject
      .create()
      .put(Fields.Type, CouchbaseSchema.JournalEntryType)
      .put(Fields.PersistenceId, pid)
      // assumed all msgs have the same writerUuid
      .put(Fields.WriterUuid, writerUuid)
      .put(Fields.Messages, JsonArray.from(messages.map(serializedMessageAsJson).asJava))

    JsonDocument.create(s"$pid-$lowestSequenceNr", insert)
  }

  def deserializeEvent[T](
      json: JsonObject,
      serialization: Serialization
  )(implicit system: ActorSystem): Future[PersistentRepr] = {
    val persistenceId = json.getString(Fields.PersistenceId)
    val sequenceNr: Long = json.getLong(Fields.SequenceNr)
    val writerUuid = json.getString(Fields.WriterUuid)
    SerializedMessage
      .fromJsonObject(serialization, json)
      .map { payload =>
        PersistentRepr(payload = payload,
                       sequenceNr = sequenceNr,
                       persistenceId = persistenceId,
                       writerUuid = writerUuid)
      }(ExecutionContexts.sameThreadExecutionContext)
  }

  def deserializeTaggedEvent(
      persistenceId: String,
      value: JsonObject,
      toSequenceNr: Long,
      serialization: Serialization
  )(implicit ec: ExecutionContext, system: ActorSystem): Future[TaggedPersistentRepr] = {
    val writerUuid = value.getString(Fields.WriterUuid)
    val sequenceNr = value.getLong(Fields.SequenceNr)
    val tags: Set[String] = value.getArray(Fields.Tags).asScala.map(_.toString).toSet
    val tagSequenceNumbers = value
      .getArray(Fields.TagSeqNrs)
      .asScala
      .map {
        case obj: JsonObject => obj.getString(Fields.Tag) -> (obj.getLong(Fields.TagSeqNr): Long)
      }
      .toMap
    SerializedMessage.fromJsonObject(serialization, value).map { payload =>
      TaggedPersistentRepr(
        PersistentRepr(payload = payload,
                       sequenceNr = sequenceNr,
                       persistenceId = persistenceId,
                       writerUuid = writerUuid),
        tags,
        tagSequenceNumbers,
        TimeBasedUUIDSerialization.fromSortableString(value.getString(Fields.Ordering))
      )
    }
  }

}

/**
 * INTERNAL API
 */
@InternalApi
private[akka] final case class SerializedMessage(identifier: Int,
                                                 manifest: String,
                                                 payload: Array[Byte],
                                                 nativePayload: OptionVal[JsonObject])

/**
 * INTERNAL API
 */
@InternalApi
private[akka] object SerializedMessage {

  import CouchbaseSchema.Fields

  def serialize(serialization: Serialization,
                event: AnyRef)(implicit system: ActorSystem): Future[SerializedMessage] = {
    val serializer = serialization.findSerializerFor(event)
    val serManifest = Serializers.manifestFor(serializer, event)

    val serId: Int = serializer.identifier

    serializer match {
      case async: AsyncSerializer =>
        Serialization.withTransportInformation(system.asInstanceOf[ExtendedActorSystem]) { () =>
          async
            .toBinaryAsync(event)
            .map(bytes => SerializedMessage(serId, serManifest, bytes, OptionVal.None))(
              ExecutionContexts.sameThreadExecutionContext
            )
        }

      case jsonSerializer: JsonSerializer =>
        Future.fromTry(
          Try(SerializedMessage(serId, serManifest, Array.emptyByteArray, OptionVal.Some(jsonSerializer.toJson(event))))
        )

      case _ =>
        Future.fromTry(Try(SerializedMessage(serId, serManifest, serialization.serialize(event).get, OptionVal.None)))
    }
  }

  def fromJsonObject(serialization: Serialization,
                     jsonObject: JsonObject)(implicit system: ActorSystem): Future[Any] = {
    val serId = jsonObject.getInt(Fields.SerializerId)
    val manifest = jsonObject.getString(Fields.SerializerManifest)

    def decodeBytes = {
      val payload = jsonObject.getString(Fields.BinaryPayload)
      Base64.getDecoder.decode(payload)
    }
    val serializer = serialization.serializerByIdentity(serId)

    serializer match {
      case async: AsyncSerializer =>
        Serialization.withTransportInformation(system.asInstanceOf[ExtendedActorSystem]) { () =>
          async.fromBinaryAsync(decodeBytes, manifest)
        }

      case jsonSerializer: JsonSerializer =>
        Future.fromTry(Try(jsonSerializer.fromJson(jsonObject.getObject(Fields.JsonPayload), manifest)))

      case _ =>
        Future.fromTry(serialization.deserialize(decodeBytes, serId, manifest))
    }
  }

}
