# Snapshot plugin

The snapshot plugin enables storing and loading snapshots for persistent actor in Couchbase (through the 
[Snapshot store plugin API](https://doc.akka.io/docs/akka/current/persistence-journals.html#snapshot-store-plugin-api).


## Configuration 
For setting up the project to use the plugin, and preparing the couchbase bucket, see @ref:[Getting Started](getting-started.md),
especially note the required index.

The snapshot plugin can then be enabled with the following configuration

```hocon
akka.persistence.snapshot-store.plugin = "couchbase-journal.snapshot"
```

The connection settings are shared with the journal plugin, see @ref:[Journal Plugin](journal.md) for details.

See [reference.conf](https://github.com/akka/akka-persistence-couchbase/blob/master/core/src/main/resources/reference.conf) 
for complete configuration option docs and defaults.

## Schema

Each snapshot is stored in a single document with an id in the form `[persistenceId]-[sequenceNr]-snapshot`.
 
The structure of the documents are like this:

```js

  {
    "type": "snapshot",
    "persistence_id": "p-1",
    "sequence_nr": 15,          // the sequence number when the snapshot was taken
    "timestamp": 1542205413616, // the unix timestamp on the node taking the snapshot when the snapshot was taken
    "ser_id": 1,                // the identifier for the serializer that was used for the snapshot
    "ser_manifest": "",         // manifest from the used serializer
    // either of these two next fields depending on if the serializer is a regular binary one - base64 encoded string
    // containing the raw serialized bytes      
    "payload_bin": "rO0ABXQACHAyLWV2dC0x",
    // or native json if using a `akka.persistence.couchbase.JsonSerializer` (see serialization section of docs)
    "payload": { native-json }
 }
```

## Usage

The snapshot plugin is used whenever a snapshot write is triggered through the 
[Akka Persistence APIs](https://doc.akka.io/docs/akka/current/persistence.html#snapshots)
 
