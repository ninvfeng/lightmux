package net.lighttools.lightmux.ui.keys

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import net.lighttools.lightmux.R
import net.lighttools.lightmux.ui.common.AddFab
import net.lighttools.lightmux.ui.common.BackButton
import net.lighttools.lightmux.ui.common.ConfirmDialog

/**
 * 密钥管理。导入一次，多台主机共用同一把钥匙。
 *
 * 私钥明文只在「新导入 / 重新导入」这一次经过界面，之后再也不回显（和主机表单一个规矩）。
 * 返回走中央栈，页面内部不写 BackHandler（CLAUDE.md 架构要点 ④）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeysScreen(
    vm: KeysViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.keys_title)) },
                navigationIcon = { BackButton(onBack) },
            )
        },
        floatingActionButton = {
            AddFab(onClick = vm::add, contentDescription = stringResource(R.string.keys_add))
        },
    ) { padding ->
        if (vm.rows.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.keys_empty), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.keys_empty_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                items(vm.rows, key = { it.key.id }) { row ->
                    KeyRowItem(row, onClick = { vm.edit(row) }, onDelete = { vm.confirmDelete(row) })
                }
            }
        }
    }

    vm.editor?.let { form ->
        KeyDialog(
            form = form,
            errors = vm.errors,
            keyFileError = vm.keyFileError,
            vm = vm,
        )
    }

    vm.deleting?.let { row ->
        ConfirmDialog(
            title = stringResource(R.string.key_delete_title, row.key.name),
            // 「还有 N 台主机在用」和「没人在用」是两种后果，不能合成一句
            message = if (row.usedBy > 0) {
                pluralStringResource(R.plurals.key_delete_message, row.usedBy, row.usedBy)
            } else {
                stringResource(R.string.key_delete_message_unused)
            },
            confirmLabel = stringResource(R.string.delete),
            onConfirm = vm::delete,
            onDismiss = vm::dismissDelete,
        )
    }
}

@Composable
private fun KeyRowItem(row: KeyRow, onClick: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(start = 16.dp, top = 12.dp, bottom = 12.dp)) {
            Text(row.key.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = when {
                    // 解不开的密钥摆在最前面说：它看着好好的，连起来却必然失败。
                    row.key.broken -> stringResource(R.string.keys_broken)
                    row.usedBy > 0 -> pluralStringResource(R.plurals.keys_used_by, row.usedBy, row.usedBy)
                    else -> stringResource(R.string.keys_unused)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (row.key.broken) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Outlined.Delete, stringResource(R.string.delete))
        }
    }
}

@Composable
private fun KeyDialog(
    form: KeyForm,
    errors: Set<KeyFormError>,
    keyFileError: KeyFileError?,
    vm: KeysViewModel,
) {
    AlertDialog(
        onDismissRequest = vm::dismissEditor,
        modifier = Modifier.imePadding(),
        title = {
            Text(
                stringResource(
                    if (form.id == null) R.string.keys_add else R.string.keys_edit_title
                )
            )
        },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = form.name,
                    onValueChange = vm::setName,
                    label = { Text(stringResource(R.string.key_name)) },
                    placeholder = { Text(form.suggestedName) },
                    singleLine = true,
                    isError = KeyFormError.Name in errors,
                    supportingText = if (KeyFormError.Name in errors) {
                        { Text(stringResource(R.string.error_key_name_required)) }
                    } else null,
                    modifier = Modifier.fillMaxWidth(),
                )
                PickKeyFileButton(onPicked = vm::loadPemFromFile, error = keyFileError)
                val pemError = errors.firstOrNull { it != KeyFormError.Name }
                OutlinedTextField(
                    value = form.pem,
                    onValueChange = vm::setPem,
                    label = { Text(stringResource(R.string.field_pem)) },
                    minLines = 3,
                    isError = pemError != null,
                    supportingText = when {
                        pemError != null -> {
                            {
                                Text(
                                    stringResource(
                                        if (pemError == KeyFormError.PemFormat) R.string.error_key_pem_format
                                        else R.string.error_key_pem_required
                                    )
                                )
                            }
                        }

                        form.keepSecret -> {
                            { Text(stringResource(R.string.secret_kept)) }
                        }

                        else -> null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = form.passphrase,
                    onValueChange = vm::setPassphrase,
                    label = { Text(stringResource(R.string.field_passphrase)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = vm::save) { Text(stringResource(R.string.save)) } },
        dismissButton = {
            TextButton(onClick = vm::dismissEditor) { Text(stringResource(R.string.cancel)) }
        },
    )
}
