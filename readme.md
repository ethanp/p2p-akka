### A simple peer-to-peer system written using the Akka framework

Much simplified version of BitTorrent.

#### Becoming a seeder
* A client loads a local file by SHA2 hashing its contents to an array of
  "chunk hashes" of size `BYTES_PER_CHUNK` as defined below.
    * Currently, `chunk`s are made up of smaller `pieces`
        * I believe this terminology is the opposite of bit-torrent's
    * The relation is as follows
        ```scala
        val BYTES_PER_PIECE = 1024
        val PIECES_PER_CHUNK = 3
        val BYTES_PER_CHUNK = BYTES_PER_PIECE * PIECES_PER_CHUNK
        ```
* A file's "`abbreviation`"" is a hash of `$filename$chunkHashes$fileLength`
* The client may upload these hashes along with the filename to all "trackers"
  it knows. Once the tracker receives it,
    * If the hash matches any file the tracker currently has by this name, or
      the tracker has no file with this name, the tracker adds this client to
      the list of known "seeders" of this file
    * Otherwise, the tracker has a file with this name but a different hash,
      and the new listing will be _rejected_

#### Becoming a leecher
* A client may request the list of seeders of a file from a tracker, and 
  receive their addresses (in the form of Akka `PeerRef`s)
* After The client initiates chunk requests from up to 4 seeders at a time
    * they send the client their chunks, and the client saves these chunks to
      a local file

### Tests

I am using TDD, please run the `scalatest` tests in the `tests` directory to
verify that your dependencies are there and everything is up and running
properly by running

```
$ sbt test
```


#### Next-Level Tests (TODO)

N.B.: These should use the `Client`'s configurable `uploadLimit` (already 
implemented) to make it easier to investigate what exactly is going on.

1. 2 peers have file but 1 *dies* part-way through transfer
2. 1 of two peers sends corrupted chunk, other does rest of file
3. Download from peer who doesn't have whole file
4. Download from peer who comes online after transfer starts
5. CLI-based progress bar by setting the `max-upload-speed` to e.g.
   2-bytes-per-second


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
    * It would also be cool...to try out Akka's new "typed actors"
        * There was a great [presentation][typed-konrad] by Konrad Malawski
          about these (which is where I got the idear)
2. DHT for peer discovery
3. Run/test across multiple JVMs/machines

[typed-konrad]: https://www.youtube.com/watch?v=WnTSuYL4_wU

### (OLD) Example usage

**This probably doesn't work anymore as I've moved to TDD. _Run the
test cases instead._** There will be a new version of the *example usage* once I
get a better command-line interface running.

Fire it up, run `Master.scala` and paste the following text into your
console. This script will transfer the textfile `testfiles/Test1.txt` from two
simulated peers to a simulated client.

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
before we can issue the command for the client to download it. `listTracker`
below is not necessary, it's just to show that the file exists (and none other
do) on the tracker.
    
    download 2 0 test1
