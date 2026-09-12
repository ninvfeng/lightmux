package net.lighttools.lightmux

import net.lighttools.lightmux.data.AuthMethod
import net.lighttools.lightmux.data.Host
import net.lighttools.lightmux.data.SshKey
import net.lighttools.lightmux.ui.host.AuthKind
import net.lighttools.lightmux.ui.host.HostForm
import net.lighttools.lightmux.ui.host.HostFormError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostFormTest {

    private fun valid() = HostForm(
        name = "gz3",
        hostname = "8.138.125.201",
        port = "22",
        username = "root",
        password = "s3cret",
    )

    @Test
    fun `填全了就没有错误`() {
        assertTrue(valid().validate().isEmpty())
    }

    @Test
    fun `主机名 用户名 凭据都是必填`() {
        val errors = HostForm().validate()
        assertEquals(
            setOf(HostFormError.Hostname, HostFormError.Username, HostFormError.Secret),
            errors,
        )
    }

    @Test
    fun `端口必须落在 1-65535`() {
        assertTrue(HostFormError.Port in valid().copy(port = "0").validate())
        assertTrue(HostFormError.Port in valid().copy(port = "65536").validate())
        assertTrue(HostFormError.Port in valid().copy(port = "").validate())
        assertTrue(HostFormError.Port in valid().copy(port = "22x").validate())
        assertTrue(valid().copy(port = " 2222 ").validate().isEmpty())
    }

    @Test
    fun `私钥方式校验的是 PEM 而不是密码`() {
        val form = valid().copy(authKind = AuthKind.PrivateKey, password = "s3cret", pem = "")
        assertTrue(HostFormError.Secret in form.validate())
        assertTrue(form.copy(pem = "-----BEGIN OPENSSH PRIVATE KEY-----").validate().isEmpty())
    }

    @Test
    fun `沿用已保存的凭据时不再要求填写`() {
        assertTrue(valid().copy(password = "", keepSecret = true).validate().isEmpty())
    }

    @Test
    fun `无需认证时密码和私钥都留空也不算错`() {
        val form = valid().copy(authKind = AuthKind.None, password = "", pem = "")
        assertTrue(form.validate().isEmpty())
        assertEquals(AuthMethod.None, form.toHost().auth)
    }

    @Test
    fun `无需认证的主机回填表单时选中对应的认证方式`() {
        val host = Host(name = "n", hostname = "h", username = "u", auth = AuthMethod.None)
        assertEquals(AuthKind.None, HostForm.of(host).authKind)
    }

    @Test
    fun `名称留空就用主机名兜底，空命令存 null`() {
        val host = valid().copy(name = "  ", loginCommand = "").toHost()
        assertEquals("8.138.125.201", host.name)
        assertNull(host.loginCommand)
    }

    @Test
    fun `保存时不改凭据就把原来的原样带回去`() {
        val existing = Host(
            id = "h1",
            name = "gz3",
            hostname = "8.138.125.201",
            username = "root",
            auth = AuthMethod.Password("old"),
        )
        val saved = HostForm.of(existing).copy(username = "deploy").toHost(existing)

        assertEquals("h1", saved.id)
        assertEquals("deploy", saved.username)
        assertEquals(AuthMethod.Password("old"), saved.auth)
    }

    @Test
    fun `复制出来的是新主机，凭据照搬但绝不覆盖源主机`() {
        val source = Host(
            id = "h1",
            name = "gz3",
            hostname = "8.138.125.201",
            port = 2222,
            username = "root",
            auth = AuthMethod.Password("old"),
            loginCommand = "tmux ls",
            proxyJumpId = "h9",
        )
        // 直接保存（用户什么都没改）就是这个复制功能最常见的用法
        val saved = HostForm.copyOf(source, listOf("gz3", "gz3 2")).toHost(source)

        // 这一条是整件事的要害：拿到源主机的 id 就等于把源主机改没了
        assertTrue(saved.id != "h1")
        assertEquals("gz3 3", saved.name)
        assertEquals("8.138.125.201", saved.hostname)
        assertEquals(2222, saved.port)
        assertEquals("root", saved.username)
        assertEquals(AuthMethod.Password("old"), saved.auth)
        assertEquals("tmux ls", saved.loginCommand)
        assertEquals("h9", saved.proxyJumpId)
    }

    @Test
    fun `凭据解不开的主机复制过去也不算已保存`() {
        val source = Host(
            id = "h1",
            name = "gz3",
            hostname = "8.138.125.201",
            username = "root",
            auth = AuthMethod.Password("", credentialLost = true, cipher = "ZZZ"),
        )
        // 沿用一份解不开的密文只会让复制出来的主机拿空密码去撞服务端，必须逼用户重填
        assertTrue(!HostForm.copyOf(source, listOf("gz3")).keepSecret)
    }

    @Test
    fun `编辑表单不回显明文密码`() {
        val existing = Host(
            name = "gz3",
            hostname = "8.138.125.201",
            username = "root",
            auth = AuthMethod.PrivateKey("PEM", "pass"),
        )
        val form = HostForm.of(existing)

        assertEquals("", form.password)
        assertEquals("", form.pem)
        assertEquals("", form.passphrase)
        assertTrue(form.keepSecret)
        assertEquals(AuthKind.PrivateKey, form.authKind)
    }

    @Test
    fun `凭据解密失败时不许显示为已保存，必须逼用户重填`() {
        val broken = Host(
            name = "gz3",
            hostname = "8.138.125.201",
            username = "root",
            // HostStore 解不出密文时会退化成空串
            auth = AuthMethod.Password(""),
        )
        val form = HostForm.of(broken)
        assertTrue(HostFormError.Secret in form.validate())
    }

    @Test
    fun `新填的凭据覆盖旧的`() {
        val existing = Host(
            id = "h1",
            name = "gz3",
            hostname = "8.138.125.201",
            username = "root",
            auth = AuthMethod.Password("old"),
        )
        val saved = HostForm.of(existing).copy(password = "new", keepSecret = false).toHost(existing)
        assertEquals(AuthMethod.Password("new"), saved.auth)
    }

    @Test
    fun `选了密钥库里的钥匙就不用再填 PEM`() {
        val form = valid().copy(authKind = AuthKind.PrivateKey, pem = "", keyId = "k1")
        assertTrue(form.validate().isEmpty())
        assertEquals(AuthMethod.PrivateKey("", null, keyId = "k1"), form.toHost().auth)
    }

    @Test
    fun `引用密钥库的主机不显示为已保存凭据`() {
        val existing = Host(
            name = "gz3",
            hostname = "8.138.125.201",
            username = "root",
            // HostStore 读出来时会把 PEM 装配进来，但这台主机的私钥源头是密钥库
            auth = AuthMethod.PrivateKey("PEM", null, keyId = "k1"),
        )
        val form = HostForm.of(existing)

        assertEquals("k1", form.keyId)
        assertTrue(!form.keepSecret)
        // 表单里没有任何要填的东西：钥匙在库里
        assertTrue(form.validate().isEmpty())
    }

    @Test
    fun `从密钥库改回手工粘贴不会捡回旧的 keyId`() {
        val existing = Host(
            id = "h1",
            name = "gz3",
            hostname = "8.138.125.201",
            username = "root",
            auth = AuthMethod.PrivateKey("PEM", null, keyId = "k1"),
        )
        val saved = HostForm.of(existing).copy(keyId = null, pem = "NEW").toHost(existing)
        assertEquals(AuthMethod.PrivateKey("NEW", null), saved.auth)
    }

    @Test
    fun `私钥口令留空存 null 而不是空串`() {
        val host = valid().copy(
            authKind = AuthKind.PrivateKey,
            pem = "  -----BEGIN-----  ",
            passphrase = "",
        ).toHost()
        assertEquals(AuthMethod.PrivateKey("-----BEGIN-----", null), host.auth)
    }

    @Test
    fun `试连时把密钥库那把钥匙的 PEM 装配进来`() {
        val form = valid().copy(authKind = AuthKind.PrivateKey, keyId = "k1")
        val keys = listOf(SshKey(id = "k1", name = "work", pem = "PEM", passphrase = "pass"))

        val auth = form.toTestHost(keys = keys).auth as AuthMethod.PrivateKey
        assertEquals("PEM", auth.pem)
        assertEquals("pass", auth.passphrase)
        assertEquals("k1", auth.keyId)
        assertTrue(!auth.credentialLost)
        // 落库的那条路仍然只写 keyId，PEM 由 HostStore 读取时装配
        assertEquals("", (form.toHost().auth as AuthMethod.PrivateKey).pem)
    }

    @Test
    fun `试连时钥匙没了算凭据丢失而不是认证失败`() {
        val form = valid().copy(authKind = AuthKind.PrivateKey, keyId = "k1")

        // 钥匙被删（列表里找不到）和钥匙自己的密文解不开（PEM 为空）是同一种下场
        val gone = form.toTestHost().auth as AuthMethod.PrivateKey
        assertTrue(gone.credentialLost)
        val broken = form.toTestHost(keys = listOf(SshKey(id = "k1", name = "work", pem = "")))
        assertTrue((broken.auth as AuthMethod.PrivateKey).credentialLost)
    }
}
