package ethanp.file

import java.security.MessageDigest
import java.util.Base64

/**
 * Ethan Petuchowski
 * 6/3/15
 */
case class Sha2(str: String = "I am the sha")
object Sha2 {
    def digestToBase64(arr: Array[Byte]) = Sha2(Base64.getEncoder.encode(arr))
    def hashOf(arr: Array[Byte]) = {
        val digester = MessageDigest.getInstance("SHA-256")
        val digest = digester.digest(arr)
        digestToBase64(digest)
    }
    def apply(bytes: Array[Byte]): Sha2 = Sha2(new String(bytes))
}
