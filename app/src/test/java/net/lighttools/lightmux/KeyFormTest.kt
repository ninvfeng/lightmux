package net.lighttools.lightmux

import net.lighttools.lightmux.data.SshKey
import net.lighttools.lightmux.ui.keys.KeyForm
import net.lighttools.lightmux.ui.keys.KeyFormError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyFormTest {

    private val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\nAAAA\n-----END OPENSSH PRIVATE KEY-----"

    @Test
    fun `名字和私钥都得有`() {
        assertEquals(setOf(KeyFormError.Name, KeyFormError.Pem), KeyForm().validate())
        assertTrue(KeyForm(name = "laptop", pem = pem).validate().isEmpty())
    }

    @Test
    fun `认不出格式的当场拦住，别等到连不上再说`() {
        val form = KeyForm(name = "laptop", pem = "PuTTY-User-Key-File-3: ssh-ed25519\n")
        assertEquals(setOf(KeyFormError.PemFormat), form.validate())
    }

    @Test
    fun `文件名可以顶替名字`() {
        val form = KeyForm(pem = pem, suggestedName = "id_ed25519")
        assertTrue(form.validate().isEmpty())
        assertEquals("id_ed25519", form.toKey().name)
    }

    @Test
    fun `只改名字时原来的 PEM 与口令原样带回去`() {
        val existing = SshKey(id = "k1", name = "laptop", pem = pem, passphrase = "pass")
        val saved = KeyForm.of(existing).copy(name = "工作本").toKey(existing)

        assertEquals("k1", saved.id)
        assertEquals("工作本", saved.name)
        assertEquals(pem, saved.pem)
        assertEquals("pass", saved.passphrase)
    }

    @Test
    fun `编辑表单不回显私钥明文`() {
        val form = KeyForm.of(SshKey(name = "laptop", pem = pem, passphrase = "pass"))
        assertEquals("", form.pem)
        assertEquals("", form.passphrase)
        assertTrue(form.keepSecret)
    }

    @Test
    fun `重新粘贴一把新钥匙就换掉旧的，口令留空即没有口令`() {
        val existing = SshKey(id = "k1", name = "laptop", pem = "old", passphrase = "pass")
        val saved = KeyForm.of(existing).copy(pem = "  $pem  ", keepSecret = false).toKey(existing)

        assertEquals(pem, saved.pem)
        assertNull(saved.passphrase)
    }

    @Test
    fun `解不开的钥匙不许显示为已保存，必须逼用户重导`() {
        val broken = SshKey(id = "k1", name = "laptop", pem = "")
        val form = KeyForm.of(broken)
        assertTrue(KeyFormError.Pem in form.validate())
    }
}
