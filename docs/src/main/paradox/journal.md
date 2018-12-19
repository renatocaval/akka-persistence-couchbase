# Journal plugin

## Features

 * All operations required by the [Akka Persistence journal plugin API](https://doc.akka.io/docs/akka/current/persistence-journals.html#journal-plugin-api) are fully supported.
 * The plugin uses Couchbase in a mostly log-oriented way i.e. data are only ever inserted but never updated 
   (deletions are made on user request only). The exception is event metadata on deletion.
 * Writes of messages are batched to optimize throughput for persistAsync. See 
 [batch writes](https://doc.akka.io/docs/akka/current/persistence.html#batch-writes) for details how to configure batch sizes. 

## Configuration

For setting up the project to use the plugin, and preparing the couchbase bucket, see @ref:[Getting Started](getting-started.md)

Enable one or more of the plugins in `application.conf` and configure the cluster connection details:

```hocon
akka.persistence.journal.plugin = "couchbase-journal.write"

couchbase-journal {
  connection {
    nodes = ["192.168.0.2", "192.168.0.3", "192.168.0.4"] # if left empty defaults to [ "localhost" ]
    username = "scott"
    password = "tiger"
  }
}
```

See [reference.conf](https://github.com/akka/akka-persistence-couchbase/blob/master/core/src/main/resources/reference.conf) 
for complete configuration option docs and defaults. 


## Schema

The events are stored in documents containing one or more atomically written events for the same persistence id with
a document id consisting of the persistence id and the lowest sequence number from the messages list.

The structure of the document is like this:

```js
 {
   "type": "journal_message",
   "persistence_id": "p2", 
   // these are unique per actor instance making it possible to detect if there are concurrent
   // writers because of a network partition for example
   "writer_uuid": "d61a4212-518a-4f75-8f27-2150f56ae60f", 
   // one or more events, more than one in the case of an atomic batched write
   "messages": [
     {
       "sequence_nr": 1,    // the sequence number of the event 
       "ser_id": 1,         // id of the serializer that was used for the payload
       "ser_manifest": "",  // potentially a manifest for the object from the serializer
       // either of these two next fields depending on if the serializer is a regular binary one - base64 encoded string
       // containing the raw serialized bytes
       "payload_bin": "rO0ABXQACHAyLWV2dC0x",
       // or native json if using a `akka.persistence.couchbase.JsonSerializer` (see serialization section of docs)
       "payload": { json-for-event },
       // these next three fields are only present if the event was tagged
       "tags": [ "tag1", "tag2" ],
       "tag-seq-nrs": {
         "tag1": 5,
         "tag2": 1
       },
       // the format of the data is the time based UUID represented as (time in utc)
       // [YYYY]-[MM]-[DD]T[HH]:[mm]:[ss]:[nanoOfSecond]_[lsb-unsigned-long-as-space-padded-string]
       // see akka.persistence.couchbase.internal.TimeBasedUUIDSerialization
       // which makes it possible to sort as a string field and get the same order as sorting the actual time based UUIDs
       "ordering": "1582-10-16T18:52:02.434002368_ 7093823767347982046"
     }
   ]
 }
```

Additionally if event deletion is performed this results in a metadata document identified by `[persistenceId]-metadata`

```js
 {
   "type": "journal_metadata",
   "deleted_to": 123 // events deleted up to this sequence number
 }
```

## Caveats

 * Deletion does not actually remove elements from the database, just mark them deleted with metadata since events by tag
   is expected to return deleted events. To actually reclaim storage space additional event deletion has to be
   done. 