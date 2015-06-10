### A simple peer-to-peer system written using the Akka framework

Much simplified version of BitTorrent.

* A client loads a local file, converts it into a file hash, and an array of
  "chunk hashes", i.e. hashes of individual chunks of the file, and uploads
  these hashes along with the file name to all "trackers" it knows.
    * If the hash matches the hash of any file the tracker currently has by
      this name, or the tracker has no file with this name, the tracker adds
      this client to the list of known "seeders" of this file.
* A client requests the list of seeders of a file from a tracker, and receives
  their addresses
* The client initiates chunk requests from 4 seeders at a time, and they send
  the client their chunks. The client saves these chunks to a local file.

### Example usage

Fire it up, run "`Master.scala`" and paste the following text into your
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


### There are 4 different "actors":

* `Tracker` --- waits for people to say they're seeding, or to ask who's
  seeding
* `Client`
    * converts local files into hashes and sends them to Tracker
    * responds to chunk requests from other clients
    * Spawns a `FileDownloader` on download request
* `FileDownloader` --- spawns `ChunkDownloader`s to download chunks
    * Tells `Client` actor when the download is finished
    * Spawns 4 concurrent `ChunkDownloaders`
    * Blacklists peers and retries chunk-downloads when they fail
* `ChunkDownloader` --- requests a specific chunk of a file from a peer
    * Recieves the chunk in "pieces" (of the chunk)
    * Upon receiving a complete "piece", the `FileDownloader` is informed, so
      that it can update the current download speed, which is printed every
      second
    * If no piece arrives beyond a timeout (15 seconds), the download *fails*
    * When the chunk transfer is complete, the `FileDownloader` is informed, os
      it can choose a new chunk to download, and a peer to download it from,
      and spawn a new `ChunkDownloader`

### Next Steps

1. Optimization: just send *hash* of the `FileInfo` around with the `Piece`s &
   `Chunk`s, not *all* the hashes
2. Better tests
3. Run in multiple JVMs
4. Test in multiple JVMs
5. Turn these into *GitHub Issues*

### Eventually...

1. DHT for peer discovery
