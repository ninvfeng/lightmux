package net.lighttools.lightmux.ssh

import com.hierynomus.sshj.key.KeyAlgorithms
import net.schmizz.sshj.AndroidConfig

/**
 * lightmux 的 sshj 配置。
 *
 * 相对 sshj 自带的 [AndroidConfig] 只改一件事：**公钥算法表**。那张表在 AndroidConfig 里
 * 被削成了 `[ssh-ed25519, ssh-rsa, ssh-dss]`——`rsa-sha2-256/512` 和整个 ECDSA 家族都没有。
 *
 * 后果是**任何 RSA 私钥都连不上 OpenSSH 8.8 以后的服务器**：那些服务器早已弃用 SHA-1 的
 * `ssh-rsa` 签名做公钥认证，而客户端只会这一种，握手正常、认证必挂。用户看到的是一句
 * 「认证失败，检查一下用户名和凭据」，可用户名和密钥都是对的——同一把钥匙 `ssh -i` 连得上。
 *
 * 这张表同时是 KEX 阶段**服务器主机密钥**的候选表，所以缺 ECDSA 还意味着只配了 ECDSA
 * 主机密钥的服务器连握手都过不去。
 *
 * 不直接换成 `DefaultConfig`：它还会去探测一批 JDK 上才有的东西，在 Android 上要么慢
 * 要么 NoClassDefFoundError（见 [SshConnection.sharedConfig]）。这里只把这一张表补回
 * 和 DefaultConfig 一致的内容，其余仍走 Android 那套精简实现。
 */
class LightmuxSshConfig : AndroidConfig() {

    /**
     * 顺序就是偏好顺序，`rsa-sha2-*` **必须排在 `ssh-rsa` 前面**，否则同一把 RSA 私钥
     * 又会挑中 SHA-1 那个变体。
     *
     * 不带 `-cert-v01@openssh.com` 变体：lightmux 不支持 OpenSSH 证书登录（PEM 里没有证书），
     * 而客户端不通告 cert 算法时，服务端自己会回退到普通主机密钥。
     */
    override fun initKeyAlgorithms() {
        setKeyAlgorithms(
            listOf(
                KeyAlgorithms.EdDSA25519(),
                KeyAlgorithms.ECDSASHANistp521(),
                KeyAlgorithms.ECDSASHANistp384(),
                KeyAlgorithms.ECDSASHANistp256(),
                KeyAlgorithms.RSASHA512(),
                KeyAlgorithms.RSASHA256(),
                // OpenSSH 7.2 之前的老服务器只认这两个，留着兜底
                KeyAlgorithms.SSHRSA(),
                KeyAlgorithms.SSHDSA(),
            )
        )
    }
}
