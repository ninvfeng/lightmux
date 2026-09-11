package net.lighttools.lightmux.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import net.lighttools.lightmux.R
import net.lighttools.lightmux.ssh.AuthChallenge

/**
 * kb-interactive（验证码 / 二次口令）追问对话框，挂在 [net.lighttools.lightmux.ui.LightmuxRoot] 根上，
 * 不挂在终端页——追问可能来自文件页的一次探测、转发页的健康检查、或主机编辑页的「测试连接」，
 * 挂在终端页的话用户一返回主页对话框就没了，而 Reader 线程还在原地等答案。
 *
 * **必须 `key(challenge)`**：[AuthChallenge] 是引用相等（理由见其类注释），同一台主机连续两次
 * 追问文字一样时（重试打错的验证码是常见场景），不 `key` 的话 Compose 会复用上一次的输入框
 * 状态，用户会看到一个残留着上一次答案（可能是密码）的输入框。
 *
 * 服务端给的 [AuthChallenge.name] / [AuthChallenge.instruction] / [AuthChallenge.prompt]
 * 原样展示，不翻译——只有服务端自己知道要问什么，翻译只会文不对题。
 */
@Composable
fun AuthChallengeDialog(
    challenge: AuthChallenge,
    onAnswer: (String) -> Unit,
    onCancel: () -> Unit,
) {
    key(challenge) {
        var text by remember { mutableStateOf("") }
        // pending 要等 ask() 的 finally 才会摘掉这一条，双击确定/取消之间那一小段空档
        // 按钮还没消失，不挡一下会把同一个答案发两次；同一个守卫也用来管返回键/点外部的取消。
        var submitted by remember { mutableStateOf(false) }
        val cancel = { if (!submitted) { submitted = true; onCancel() } }
        val confirm = { if (!submitted) { submitted = true; onAnswer(text) } }

        AlertDialog(
            onDismissRequest = cancel,
            title = { Text(challenge.name.ifBlank { stringResource(R.string.auth_challenge_title) }) },
            text = {
                Column {
                    if (challenge.instruction.isNotBlank()) {
                        Text(challenge.instruction, modifier = Modifier.padding(bottom = 8.dp))
                    }
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text(challenge.prompt) },
                        singleLine = true,
                        visualTransformation = if (challenge.echo) VisualTransformation.None else PasswordVisualTransformation(),
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = !submitted, onClick = confirm) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(enabled = !submitted, onClick = cancel) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
