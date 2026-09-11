package net.lighttools.lightmux.ssh

/**
 * 判断 keyboard-interactive 的一条追问是不是"就是在问密码"——只有这种情况才允许用已保存的
 * 密码自动填答，别的一律要真人输入。
 *
 * 两个必须堵住的坑：
 * 1. `contains("password")` 会命中 pam_oath 的 `One-time password (OATH) for 'user':`
 *    和 Duo 的 `Password or option:`——真按这个自动填，等于把保存的密码当验证码发出去，
 *    验证码错了不说，密码还泄露给了一个本不该收到它的提示。
 * 2. 密码已经在别的认证方法里试过失败了，服务端才把 kb-interactive 的 `Password:` 抛回来——
 *    这时候原样重发等于给 fail2ban 白送一次命中，见 [shouldAutofill] 的 `allowedMethods` 判定。
 */
object ChallengePrompt {

    private val otpKeywords = listOf(
        "one-time", "onetime", "otp", "verification", "token", "code", "验证码", "一次性",
    )

    private val passwordWords = setOf("password", "passwd", "密码", "口令")

    /** 归一化后与密码词精确相等才算——"包含"会把 OTP/Duo 的提示语一起吞进来。 */
    fun isPassword(prompt: String): Boolean {
        val normalized = normalize(prompt)
        if (otpKeywords.any { normalized.contains(it) }) return false
        return normalized in passwordWords
    }

    /**
     * @param allowedMethods 当前 `client.userAuth.allowedMethods`——不含 `"password"` 说明
     *   密码认证方法根本没在这台主机上试过（纯 OTP/堡垒机表单），自动填是安全的；含有则说明
     *   我们的密码刚刚在这里失败过，不能重发。
     * @param used 本次 kb-interactive 会话里是否已经自动填过一次——只填第一条，避免死循环重填。
     */
    fun shouldAutofill(prompt: String, allowedMethods: Collection<String>, used: Boolean): Boolean =
        !used && "password" !in allowedMethods && isPassword(prompt)

    private fun normalize(prompt: String): String =
        prompt.trim()
            .lowercase()
            .substringBefore(" for ")
            .trim()
            .trimEnd(':', '：')
            .trim()
}
