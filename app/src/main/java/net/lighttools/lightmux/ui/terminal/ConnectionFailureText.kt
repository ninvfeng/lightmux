package net.lighttools.lightmux.ui.terminal

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import net.lighttools.lightmux.R

/**
 * 失败原因的文案。
 *
 * 和按钮分开，是因为 [ConnectionFailure.ProxyJumpFailed] 要把内层原因的文案**套进自己那句里**
 * （「经跳板机 bastion 失败：认证失败」），而按钮该指向哪台主机是另一回事。
 *
 * 放在这里而不是终端页里：主机编辑页的「测试」也要把同一个分类翻成同一套话——
 * 同一个失败在两处说法不一样，用户会以为遇到的是两回事。
 */
@Composable
fun connectionFailureText(failure: ConnectionFailure, endpoint: String): String = when (failure) {
    // 终端页走的是阻断式对话框，这句只在编辑页的测试结果里露面
    is ConnectionFailure.HostKeyChanged ->
        stringResource(R.string.error_host_key_changed, failure.endpoint, failure.expected, failure.actual)

    ConnectionFailure.AuthFailed -> stringResource(R.string.error_auth_failed)
    ConnectionFailure.CredentialLost -> stringResource(R.string.error_credential_lost)
    ConnectionFailure.AgentUnsupported -> stringResource(R.string.error_auth_agent_unsupported)
    ConnectionFailure.ExecTimeout -> stringResource(R.string.error_exec_timeout)
    is ConnectionFailure.ProxyJumpFailed -> failure.jumpName?.let { name ->
        stringResource(R.string.error_proxy_jump, name, connectionFailureText(failure.reason, endpoint))
    } ?: stringResource(R.string.error_proxy_jump_broken)

    is ConnectionFailure.Other -> failure.message
        ?: stringResource(R.string.error_connect_failed, endpoint)
}
