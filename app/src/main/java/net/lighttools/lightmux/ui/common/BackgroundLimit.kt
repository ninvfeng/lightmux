package net.lighttools.lightmux.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import net.lighttools.lightmux.R
import net.lighttools.lightmux.service.BackgroundLimits

/**
 * 系统有没有放行后台行为。授权页是系统的，用户在那儿点完只会 `ON_RESUME` 回来，
 * 所以每次回前台都重问一遍——否则刚授完权，提示条还挂在那儿。
 */
@Composable
fun rememberBackgroundExempt(): Boolean {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var exempt by remember { mutableStateOf(BackgroundLimits.isExempt(context)) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) exempt = BackgroundLimits.isExempt(context)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return exempt
}

/**
 * 后台联网受限的提示条，系统已放行时整条不渲染。
 *
 * 不用 [ErrorBanner] 的错误色：这不是出错了，是「照现在的设置用下去会断」。
 * 染成红的，用户第一反应是转发坏了。
 */
@Composable
fun BackgroundLimitBanner(modifier: Modifier = Modifier) {
    if (rememberBackgroundExempt()) return
    val context = LocalContext.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.background_limit_message),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = { BackgroundLimits.requestExemption(context) }) {
                Text(stringResource(R.string.background_limit_action))
            }
        }
    }
}
