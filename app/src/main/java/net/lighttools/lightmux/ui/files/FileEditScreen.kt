package net.lighttools.lightmux.ui.files

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.lighttools.lightmux.R
import net.lighttools.lightmux.sftp.SftpPath
import net.lighttools.lightmux.sftp.SftpRepository
import net.lighttools.lightmux.ui.common.BackButton
import net.lighttools.lightmux.ui.common.ConfirmDialog
import net.lighttools.lightmux.ui.common.ErrorBanner
import net.lighttools.lightmux.ui.common.Spinner
import net.lighttools.lightmux.ui.theme.MonoFamily

/**
 * 小文本文件的应用内编辑器（PRD §4.4）。
 *
 * 只处理「改一行配置、加一个环境变量」这种事，**不是要替代 vim**：
 * 超过 [SftpRepository.MAX_EDITABLE_BYTES] 或含 NUL 的文件在这里只给一句解释，不给编辑框。
 *
 * 未保存时的返回拦截同样走 `LightmuxRoot` 那个唯一的 `BackHandler`，本页不写第二个。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileEditScreen(
    vm: FileEditViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(vm) { vm.start() }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = vm.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = vm.path,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    // 顶栏的返回和系统返回键必须一个语义，否则「有改动时点哪个才会问我」变成玄学
                    BackButton(onClick = { if (!vm.requestExit()) onBack() })
                },
                actions = {
                    if (vm.saving) {
                        Spinner(modifier = Modifier.padding(12.dp).size(20.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = vm::save, enabled = vm.dirty) {
                            Icon(Icons.Default.Save, stringResource(R.string.save))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // 主机没了就只说这一句，不给关闭按钮：这条横幅撤掉之后页面上什么也不剩
            if (vm.hostMissing) ErrorBanner(message = stringResource(R.string.error_host_missing))
            vm.error?.let { error ->
                ErrorBanner(
                    message = error,
                    actionLabel = stringResource(R.string.close),
                    onAction = vm::clearError,
                )
            }

            when {
                vm.loading -> Centered { Spinner() }

                vm.tooLarge != null -> Centered {
                    Notice(
                        stringResource(
                            R.string.file_edit_too_large,
                            SftpPath.humanSize(vm.tooLarge ?: 0L),
                            SftpPath.humanSize(SftpRepository.MAX_EDITABLE_BYTES),
                        )
                    )
                }

                vm.binary -> Centered { Notice(stringResource(R.string.file_edit_binary)) }

                vm.editable -> OutlinedTextField(
                    value = vm.text,
                    onValueChange = vm::onTextChanged,
                    // 不套 verticalScroll：TextField 自己会在受限高度里滚，外面再套一层
                    // 会让它拿到无限高度，长文件直接把整棵布局撑爆
                    modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp),
                    // 配置文件靠对齐读，等宽字体是刚需；字号压小一点，一屏能多看几行
                    textStyle = TextStyle(fontFamily = MonoFamily, fontSize = 13.sp),
                )
            }
        }
    }

    if (vm.exitRequested) {
        ConfirmDialog(
            title = stringResource(R.string.file_edit_discard_title),
            message = stringResource(R.string.file_edit_discard_message, vm.name),
            confirmLabel = stringResource(R.string.file_edit_discard),
            onConfirm = {
                vm.dismissExit()
                onBack()
            },
            onDismiss = vm::dismissExit,
        )
    }
}

@Composable
private fun Notice(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}
