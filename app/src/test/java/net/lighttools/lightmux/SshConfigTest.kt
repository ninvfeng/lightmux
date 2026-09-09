package net.lighttools.lightmux

import net.lighttools.lightmux.ssh.LightmuxSshConfig
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 公钥算法表的守卫。
 *
 * 这张表曾经是 sshj [net.schmizz.sshj.AndroidConfig] 给的 `[ssh-ed25519, ssh-rsa, ssh-dss]`，
 * 于是**所有 RSA 私钥都连不上 OpenSSH 8.8 以后的服务器**（那边不再接受 SHA-1 的 `ssh-rsa`
 * 签名），而症状只是一句「认证失败」。这类问题在本机跑不了真机、也连不了真服务器，
 * 只能靠把算法表钉在这里。
 */
class SshConfigTest {

    private val names = LightmuxSshConfig().keyAlgorithms.map { it.name }

    @Test
    fun `RSA 私钥有 sha2 签名可用`() {
        assertTrue("rsa-sha2-512" in names)
        assertTrue("rsa-sha2-256" in names)
    }

    @Test
    fun `sha2 变体排在 SHA-1 的 ssh-rsa 前面`() {
        // 顺序就是偏好顺序，排反了等于没修
        assertTrue(names.indexOf("rsa-sha2-512") < names.indexOf("ssh-rsa"))
        assertTrue(names.indexOf("rsa-sha2-256") < names.indexOf("ssh-rsa"))
    }

    @Test
    fun `ECDSA 与 ed25519 都在`() {
        // 这张表也是 KEX 阶段的服务器主机密钥候选表，缺 ECDSA 会连握手都过不去
        assertTrue("ecdsa-sha2-nistp256" in names)
        assertTrue("ecdsa-sha2-nistp384" in names)
        assertTrue("ecdsa-sha2-nistp521" in names)
        assertTrue("ssh-ed25519" in names)
    }
}
