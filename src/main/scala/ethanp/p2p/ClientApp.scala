package ethanp.p2p

import java.io.File

import scala.collection.mutable

/**
 * Ethan Petuchowski
 * 6/3/15
 *
 * I'm thinking this is essentially where "main()" for a client is going to be
 */
class ClientApp {
    /* TODO read this in from a JSON file within THIS project */
    /** this file says where the LocalP2PFile JSON files are stored on my local file system */
    val localP2PFiles = mutable.HashSet.empty[File]
}
