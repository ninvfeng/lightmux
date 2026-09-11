package net.lighttools.lightmux.ui.files

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import net.lighttools.lightmux.R
import net.lighttools.lightmux.ui.common.Spinner

/** 单文件预检就一次 ls，100ms 内回来；不加延迟的话一个转两下就消失的模态框比不弹更让人心慌。 */
private const val SCAN_HINT_DELAY_MS = 300L

/** 一屏最多列几条冲突名字。按钮只有两个，读完清单也改变不了选择，列太多没意义。 */
private const val MAX_LISTED_CONFLICTS = 8

@Composable
internal fun UploadScanningDialog(onCancel: () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(SCAN_HINT_DELAY_MS)
        visible = true
    }
    if (!visible) return
    AlertDialog(
        onDismissRequest = onCancel,
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Spinner(Modifier.size(24.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.files_upload_checking))
            }
        },
        confirmButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * 同名文件怎么办：全部覆盖 / 全部跳过。
 *
 * **不做逐项勾选**：一次多选十几个文件时逐个问就是十几次打断，而用户的意图几乎总是整体性的。
 * 不摆第三个「取消」按钮——返回键 / 点外部就是「什么都不改」的出口（[onDismiss]），
 * 且 M3 的按钮行放不下两个 TextButton 时不会像两槽之间那样自动换行，硬塞第三个只会把文字挤成省略号。
 */
@Composable
internal fun UploadConflictDialog(
    names: List<String>,
    onOverwrite: () -> Unit,
    onSkip: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.files_conflict_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                names.take(MAX_LISTED_CONFLICTS).forEach { name ->
                    // AlertDialog 的 text 槽不滚动，长名字换行能把按钮顶出屏幕；
                    // 中间省略而不是尾部——这里是 `css/app.css` 这种相对路径，扩展名不能丢
                    Text(
                        name,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (names.size > MAX_LISTED_CONFLICTS) {
                    Text(
                        stringResource(R.string.files_conflict_more, names.size - MAX_LISTED_CONFLICTS),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onOverwrite) { Text(stringResource(R.string.files_conflict_overwrite)) } },
        dismissButton = { TextButton(onClick = onSkip) { Text(stringResource(R.string.files_conflict_skip)) } },
    )
}
