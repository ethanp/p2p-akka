### A simple peer-to-peer system written using the Akka framework

Simplified version of BitTorrent.

#### Becoming a seeder
* A client loads a local file by SHA2 hashing its contents to an array of
  "chunk hashes" of size `BYTES_PER_CHUNK` as defined below.
    * Currently, `chunk`s are made up of smaller `pieces`
        * I believe this terminology is the opposite of bit-torrent's
    * The relation is as follows (from file `P2PFile.scala`, class `LocalP2PFile`)
        ```scala
        val BYTES_PER_PIECE = 1024
        val PIECES_PER_CHUNK = 3
        val BYTES_PER_CHUNK = BYTES_PER_PIECE * PIECES_PER_CHUNK
        ```
* A file's "`abbreviation`" is a hash of `$filename$chunkHashes$fileLength`
* The client may upload these hashes along with the filename to all "tracker"
  servers it knows. Once a tracker receives it,
    * If the tracker has a file with this name but a different hash, the new
      listing will be _rejected_
    * Otherwise, the tracker adds this client to the list of known "seeders"
      of this file


#### Becoming a leecher

* A __swarm__ is the known set of seeders and leechers of a particular file
* A client receives addresses of members of a file's _swarm_ from a tracker
  (in the form of Akka `PeerRef`s)
    * Upon doing this, the tracker adds the requester to the _leecher set_ for
      this _swarm_
* The client initiates chunk requests from up to `maxConcurrentChunks`
  peers at a time
* They each respond with their chunks, which the client saves to a local file


### Tests

Development is losely _test-driven_, so please run the `scalatest` tests in
the `tests` directory to verify that your dependencies are there and
everything is up and running properly by running the following shell command
in the repository's base directory

```
$ sbt test
```

The tests also form a decent presentation of how everything works.

#### Next-Level Tests (TODO)

N.B.: These should use the `Client`'s configurable `uploadLimit` (already
implemented) to make it easier to investigate what exactly is going on.

1. 2 peers have file but 1 *dies* part-way through transfer
2. 1 of two peers sends corrupted chunk, other does rest of file
3. Download from peer who comes online after transfer starts
4. Run across multiple JVMs
5. Run across multiple Linux VMs
6. Run across multiple physical nodes
7. Benchmarks

### Next-Level features (TODO)

* CLI-based progress bar
* Full-fledged GUI
* DHT


### Actor Structure

* `Tracker` --- responds to
    1. requests to seed
    2. requests for seeders
* `Client`
    * converts local available files into hashes and sends them to Tracker
    * Spawns a `ChunkReplyer` upon chunk requests from other clients
    * Spawns a `FileDownloader` upon download request from user
* `FileDownloader` --- spawns `ChunkDownloader`s to download chunks
    * Tells `Client` actor when the download is finished
    * Spawns up to 4 concurrent `ChunkDownloaders`
    * Blacklists peers and retries chunk-downloads when they fail (_untested_)
* `ChunkDownloader` --- requests a specific chunk of a file from a peer
    * Receives the chunk in "pieces" (of the chunk)
    * Upon receiving a complete "piece", the `FileDownloader` is informed, so
      that it can update the current download speed, which is printed every
      second
    * If no piece arrives beyond a timeout (15 seconds), the download *fails*
    * When the chunk transfer is complete, the `FileDownloader` is informed, os
      it can choose a new chunk to download, and a peer to download it from,
      and spawn a new `ChunkDownloader`

### Eventually...

1. Replace Actors with Akka StateMachines
    * The whole client<->peer protocol is really just a "parallel" state
      machine
    * It would also be cool...to try out Akka's new "Akka Typed" actors
        * There was a great [presentation][typed-konrad] by Konrad Malawski
          about these (which is where I got the idear)
2. Replace the whole `Piece` idea with Akka Streams
3. DHT for peer discovery
4. Run/test across multiple JVMs/machines

[typed-konrad]: https://www.youtube.com/watch?v=WnTSuYL4_wU

### (OLD) Example usage

**This probably doesn't work anymore as I've moved to TDD. _Run the test cases
instead._** There will be a new version of the *example usage* once I get a
better command-line interface running.

Fire it up, run `Master.scala` and paste the following text into your console.
This script will transfer the textfile `testfiles/Test1.txt` from two simulated
peers to a simulated client.

    newTracker
    newClient
    newClient
    newClient
    giveClientTracker 0 0
    giveClientTracker 1 0
    giveClientTracker 2 0
    addFile 0 testfiles/Test1.txt test1
    addFile 1 testfiles/Test1.txt test1

Paste that in first; we need to wait (a few milliseconds) for the peers to
generate hashes of each chunk of the file, and upload them to the tracker,
before we can issue the command for the client to download it.

    download 2 0 test1
