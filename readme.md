### A simple peer-to-peer system written using the Akka framework

I made the design decision not to leverage the Cluster framework (within Akka) and the
built-in DHT stuff etc for now, until I have something working and decent.

Right now, all nodes live in one JVM, and communicate via Akka's in-JVM message-passing
infrastructure.

Making it truly awesome is "future work".

### Example usage

Fire it up, and paste the following text into your console.
This script will transfer the textfile `testfiles/Test1.txt` between two simulated peers.

    newTracker
    newClient
    newClient
    giveClientTracker 0 0
    giveClientTracker 0 0
    addFile 0 testfiles/Test1.txt test1
    listTracker 1 0
    download 1 0 test1
