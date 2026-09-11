package net.lighttools.lightmux

import net.lighttools.lightmux.ssh.ChallengePrompt
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChallengePromptTest {

    @Test
    fun `纯密码提示才算密码`() {
        assertTrue(ChallengePrompt.isPassword("Password:"))
        assertTrue(ChallengePrompt.isPassword("Password: "))
        assertTrue(ChallengePrompt.isPassword("password"))
        assertTrue(ChallengePrompt.isPassword("密码："))
        assertTrue(ChallengePrompt.isPassword("口令:"))
    }

    @Test
    fun `pam_oath 的一次性密码提示不能被当成密码`() {
        // 关键词是 "password"，naive 的 contains 判定会中招——真填了等于把保存的密码当验证码发出去
        assertFalse(ChallengePrompt.isPassword("One-time password (OATH) for 'user':"))
    }

    @Test
    fun `Duo 的选项提示不能被当成密码`() {
        // 归一化之后是 "password or option"，和 "password" 不相等，靠精确匹配挡住
        assertFalse(ChallengePrompt.isPassword("Password or option:"))
    }

    @Test
    fun `带用户名后缀的密码提示仍然认得出`() {
        assertTrue(ChallengePrompt.isPassword("Password for 'root':"))
    }

    @Test
    fun `验证码类关键词一律排除`() {
        assertFalse(ChallengePrompt.isPassword("Verification code:"))
        assertFalse(ChallengePrompt.isPassword("OTP:"))
        assertFalse(ChallengePrompt.isPassword("Token:"))
        assertFalse(ChallengePrompt.isPassword("验证码："))
        assertFalse(ChallengePrompt.isPassword("一次性密码："))
    }

    @Test
    fun `既不是密码也不是验证码的提示两边都不占`() {
        assertFalse(ChallengePrompt.isPassword("Username:"))
        assertFalse(ChallengePrompt.isPassword("Enter PIN:"))
    }

    @Test
    fun `密码已经在这台主机试过就不能自动重填`() {
        // allowedMethods 里有 password，说明密码认证方法刚刚在这里失败过，原样重发只会喂给 fail2ban
        assertFalse(ChallengePrompt.shouldAutofill("Password:", listOf("password", "keyboard-interactive"), used = false))
    }

    @Test
    fun `纯 OTP 主机没试过密码才允许自动填第一条`() {
        assertTrue(ChallengePrompt.shouldAutofill("Password:", listOf("keyboard-interactive"), used = false))
    }

    @Test
    fun `本轮已经自动填过一次就不再重填`() {
        assertFalse(ChallengePrompt.shouldAutofill("Password:", listOf("keyboard-interactive"), used = true))
    }

    @Test
    fun `不是密码提示无论如何不自动填`() {
        assertFalse(ChallengePrompt.shouldAutofill("One-time password (OATH) for 'user':", listOf("keyboard-interactive"), used = false))
        assertFalse(ChallengePrompt.shouldAutofill("Verification code:", listOf("keyboard-interactive"), used = false))
    }
}
