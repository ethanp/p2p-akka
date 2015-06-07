### A simple peer-to-peer system written using the Akka framework

I made the design decision not to leverage the Cluster framework (within Akka) and the
built-in DHT stuff etc for now, until I have something working and decent.

Right now, all nodes live in one JVM, and communicate via Akka's in-JVM message-passing
infrastructure.

Making it truly awesome is "future work".

### Example usage

Fire it up, and paste the following text into your console.
This script will transfer the textfile `testfiles/Test1.txt`
from two simulated peers to a simulated client.

    newTracker
    newClient
    newClient
    newClient
    giveClientTracker 0 0
    giveClientTracker 1 0
    giveClientTracker 2 0
    addFile 0 testfiles/Test1.txt test1
    addFile 1 testfiles/Test1.txt test1
    
Paste that in first; we need to wait (a few milliseconds) for the peers to generate hashes
of each chunk of the file, and upload them to the tracker, before we can issue the command
for the client to download it. `listTracker` below is not necessary, it's just to show that
the file exists (and none other do) on the tracker.
    
    download 2 0 test1
