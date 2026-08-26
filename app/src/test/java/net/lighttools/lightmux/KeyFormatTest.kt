package net.lighttools.lightmux

import net.lighttools.lightmux.data.KeyFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyFormatTest {

    @Test
    fun `认得出三种能用的私钥`() {
        assertEquals(KeyFormat.OpenSsh, KeyFormat.of("-----BEGIN OPENSSH PRIVATE KEY-----\nAAAA\n"))
        assertEquals(KeyFormat.Pkcs8, KeyFormat.of("-----BEGIN PRIVATE KEY-----\nMIIE\n"))
        assertEquals(KeyFormat.Pkcs8, KeyFormat.of("-----BEGIN ENCRYPTED PRIVATE KEY-----\nMIIE\n"))
        assertEquals(KeyFormat.Pkcs1, KeyFormat.of("-----BEGIN RSA PRIVATE KEY-----\nMIIE\n"))
        assertEquals(KeyFormat.Pkcs1, KeyFormat.of("-----BEGIN EC PRIVATE KEY-----\nMHc\n"))
        assertTrue(KeyFormat.of("-----BEGIN RSA PRIVATE KEY-----").supported)
    }

    @Test
    fun `前面有注释行也认`() {
        val pem = "# my laptop key\n\n-----BEGIN OPENSSH PRIVATE KEY-----\nAAAA\n"
        assertEquals(KeyFormat.OpenSsh, KeyFormat.of(pem))
    }

    @Test
    fun `putty 单独认出来，好给一句能照做的提示`() {
        val ppk = "PuTTY-User-Key-File-3: ssh-ed25519\nEncryption: none\n"
        assertEquals(KeyFormat.Putty, KeyFormat.of(ppk))
        assertFalse(KeyFormat.of(ppk).supported)
    }

    @Test
    fun `公钥和随便什么文本都不是私钥`() {
        assertEquals(KeyFormat.Unknown, KeyFormat.of("ssh-ed25519 AAAAC3Nz user@host"))
        assertEquals(KeyFormat.Unknown, KeyFormat.of("-----BEGIN CERTIFICATE-----\nMIID\n"))
        assertEquals(KeyFormat.Unknown, KeyFormat.of(""))
        assertFalse(KeyFormat.of("hello").supported)
    }

    @Test
    fun `只看开头几行，正文里的字样不算数`() {
        val decoy = buildString {
            repeat(20) { appendLine("noise") }
            appendLine("-----BEGIN OPENSSH PRIVATE KEY-----")
        }
        assertEquals(KeyFormat.Unknown, KeyFormat.of(decoy))
    }
}
