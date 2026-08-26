package net.lighttools.lightmux.ui.terminal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.lighttools.lightmux.R
import net.lighttools.lightmux.data.QuickBar
import net.lighttools.lightmux.data.QuickCommand
import net.lighttools.lightmux.data.QuickCustomKey
import net.lighttools.lightmux.data.QuickCustomKeys
import net.lighttools.lightmux.data.QuickKey
import net.lighttools.lightmux.data.QuickSlot
import net.lighttools.lightmux.ui.common.DragHandle
import net.lighttools.lightmux.ui.common.rememberReorderState
import net.lighttools.lightmux.ui.common.reorderableRow

/**
 * 快捷命令表。点一条填进终端，拖把手调顺序、铅笔改、垃圾桶删。
 *
 * 顺序能调是因为新命令一律追加在末尾：不给排序，想把常敲的那条挪到手边就只能删了重加，
 * 而重加又落到末尾——等于整张表重排一遍。排序方式与 [QuickKeysSheet] 统一走拖动。
 *
 * @param onPick 默认只把命令填进去，那条命令自己勾了「按下即执行」才补回车（接线见 [ExtraKeysBar]）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickCommandsSheet(
    commands: List<QuickCommand>,
    onPick: (QuickCommand) -> Unit,
    onChange: (List<QuickCommand>) -> Unit,
    onDismiss: () -> Unit,
) {
    // null = 没在编辑；[NEW_COMMAND] = 在新增
    var editing by remember { mutableStateOf<Int?>(null) }
    val reorder = rememberReorderState()
    // 拖动中只改这份本地的，松手才落盘——每换一格写一次 DataStore 的话，
    // 列表得等回灌才更新，手指底下那行会一格一格地追着走
    var order by remember(commands) { mutableStateOf(commands) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
            item {
                SheetHeader(
                    title = stringResource(R.string.quick_commands),
                    actionLabel = stringResource(R.string.quick_command_add),
                    onAction = { editing = NEW_COMMAND },
                )
            }

            if (order.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.quick_commands_empty),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            itemsIndexed(order) { index, command ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .reorderableRow(reorder, index)
                        .padding(end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DragHandle(
                        state = reorder,
                        index = index,
                        lastIndex = order.lastIndex,
                        onMove = { from, to -> order = order.moved(from, to) },
                        onDrop = { onChange(order) },
                    )
                    Text(
                        text = command.text,
                        // 可点区只给文本，不给整行：把手和右边那两个按钮各管各的，
                        // 想拖一下顺序结果把命令填进了终端，这误触很吓人
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onPick(command) }
                            .padding(vertical = 12.dp),
                        // 等宽：命令里的空格与对齐是有意义的，比例字体会把 `-la` 挤成一团
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // 点下去就会跑的那几条得看得出来——它们和「只填进去」的行为差别很大
                    if (command.enter) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardReturn,
                            contentDescription = stringResource(R.string.quick_enter_on_tap),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { editing = index }) {
                        Icon(Icons.Filled.Edit, stringResource(R.string.edit))
                    }
                    IconButton(onClick = { onChange(order.without(index)) }) {
                        Icon(Icons.Filled.Delete, stringResource(R.string.delete))
                    }
                }
            }

            item { Spacer(Modifier.height(12.dp)) }
        }
    }

    editing?.let { index ->
        val adding = index == NEW_COMMAND
        QuickCommandDialog(
            title = stringResource(if (adding) R.string.quick_command_add else R.string.edit),
            initial = order.getOrElse(index) { QuickCommand("") },
            onConfirm = { command ->
                onChange(if (adding) order + command else order.replaced(index, command))
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

/** 命令的编辑框。比通用的 [net.lighttools.lightmux.ui.common.InputDialog] 多一个「按下即执行」。 */
@Composable
private fun QuickCommandDialog(
    title: String,
    initial: QuickCommand,
    onConfirm: (QuickCommand) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable(initial) { mutableStateOf(initial.text) }
    var enter by rememberSaveable(initial) { mutableStateOf(initial.enter) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.quick_command_label)) },
                    singleLine = true,
                )
                EnterOnTapRow(checked = enter, onCheckedChange = { enter = it })
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(QuickCommand(text.trim(), enter)) },
                enabled = text.isNotBlank(),
            ) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** 「按下即执行」的勾选行。整行可点：20dp 见方的勾选框在手机上是个很难瞄的目标。 */
@Composable
private fun EnterOnTapRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(
            text = stringResource(R.string.quick_enter_on_tap),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * 快捷栏管理：拖把手排序、`✕` 移除，外加把没用上的键加回来、自己造几格新的。
 *
 * 自定义键的增删改**只写库**（[onCustomKeysChange]），栏的顺序自己跟上——
 * 见 [QuickBar.decodeSlots]：库里有而顺序串没提到的补在栏尾，顺序串里认不出的那格自然消失。
 * 也因此自定义键的 `✕` 是「删掉这个键」而不是「从栏上撤下」：留一个栏上看不见的键在库里，
 * 用户既按不到也改不掉，不如删干净。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun QuickKeysSheet(
    slots: List<QuickSlot>,
    onChange: (List<QuickSlot>) -> Unit,
    onCustomKeysChange: (List<QuickCustomKey>) -> Unit,
    onDismiss: () -> Unit,
) {
    val reorder = rememberReorderState()
    // 同命令表：拖动中只改本地这份，松手才落盘
    var order by remember(slots) { mutableStateOf(slots) }
    // null = 没在编辑；[NEW_CUSTOM_KEY] = 在新增
    var editingCustom by remember { mutableStateOf<QuickCustomKey?>(null) }
    val presets = order.filterIsInstance<QuickSlot.Preset>().map { it.key }
    val available = QuickKey.entries.filterNot { it in presets }
    val customs = QuickBar.customsOf(order)

    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
            item {
                SheetHeader(
                    title = stringResource(R.string.quick_bar_manage),
                    actionLabel = stringResource(R.string.quick_bar_reset),
                    onAction = { onChange(QuickBar.defaultSlots()) },
                )
            }

            // 不设 key：拖动中列表一直在重排，按 key 复用会把正在收手势的节点搬走（见 [ReorderState]）
            itemsIndexed(order) { index, slot ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .reorderableRow(reorder, index)
                        .padding(end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DragHandle(
                        state = reorder,
                        index = index,
                        lastIndex = order.lastIndex,
                        onMove = { from, to -> order = order.moved(from, to) },
                        onDrop = { onChange(order) },
                    )
                    QuickSlotCap(slot)
                    val custom = (slot as? QuickSlot.Custom)?.key
                    if (custom != null) {
                        // 发什么看不见就等于要靠记，几格自定义键排在一起会分不清谁是谁
                        Text(
                            text = custom.text,
                            modifier = Modifier.weight(1f).padding(start = 12.dp),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        IconButton(onClick = { editingCustom = custom }) {
                            Icon(Icons.Filled.Edit, stringResource(R.string.edit))
                        }
                        IconButton(onClick = { onCustomKeysChange(customs - custom) }) {
                            Icon(Icons.Filled.Delete, stringResource(R.string.delete))
                        }
                    } else {
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { onChange(order - slot) }) {
                            Icon(Icons.Filled.Close, stringResource(R.string.remove))
                        }
                    }
                }
            }

            item {
                TextButton(
                    onClick = { editingCustom = NEW_CUSTOM_KEY },
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Icon(Icons.Filled.Add, null, modifier = Modifier.padding(end = 4.dp))
                    Text(stringResource(R.string.quick_key_custom_add))
                }
            }

            if (available.isNotEmpty()) {
                item {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.quick_bar_add_key),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(
                            modifier = Modifier.padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            available.forEach { key ->
                                QuickKeyCap(key, onClick = { onChange(order + QuickSlot.Preset(key)) })
                            }
                        }
                    }
                }
            }
        }
    }

    editingCustom?.let { editing ->
        val adding = editing === NEW_CUSTOM_KEY
        CustomKeyDialog(
            title = stringResource(if (adding) R.string.quick_key_custom_add else R.string.edit),
            initial = editing,
            onConfirm = { key ->
                val saved = if (adding) key.copy(id = QuickCustomKeys.nextId(customs)) else key
                onCustomKeysChange(
                    if (adding) customs + saved else customs.map { if (it.id == saved.id) saved else it },
                )
                editingCustom = null
            },
            onDismiss = { editingCustom = null },
        )
    }
}

/**
 * 自定义键的编辑框。
 *
 * 键帽与内容分成两栏而不是「拿内容当键帽」：`claude --resume` 画不进一格键，
 * 而 `c1` 这种两个字符的键帽正是用户自己心里那套速记。
 */
@Composable
private fun CustomKeyDialog(
    title: String,
    initial: QuickCustomKey,
    onConfirm: (QuickCustomKey) -> Unit,
    onDismiss: () -> Unit,
) {
    var label by rememberSaveable(initial) { mutableStateOf(initial.label) }
    var text by rememberSaveable(initial) { mutableStateOf(initial.text) }
    var enter by rememberSaveable(initial) { mutableStateOf(initial.enter) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it.take(QuickCustomKeys.MAX_LABEL_LENGTH) },
                    label = { Text(stringResource(R.string.quick_key_custom_label)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.quick_key_custom_text)) },
                    singleLine = true,
                )
                EnterOnTapRow(checked = enter, onCheckedChange = { enter = it })
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(initial.copy(label = label.trim(), text = text.trim(), enter = enter)) },
                enabled = label.isNotBlank() && text.isNotBlank(),
            ) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun SheetHeader(title: String, actionLabel: String, onAction: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
        )
        TextButton(onClick = onAction) { Text(actionLabel) }
    }
}

/** 编辑中的下标哨兵：不是任何一条已有命令。 */
private const val NEW_COMMAND = -1

/** 「在新增」的哨兵。按引用（`===`）判定，所以不能换成同值的另一个实例。 */
private val NEW_CUSTOM_KEY = QuickCustomKey(id = "", label = "", text = "")

private fun <T> List<T>.without(index: Int): List<T> = toMutableList().also { it.removeAt(index) }

private fun <T> List<T>.replaced(index: Int, value: T): List<T> = toMutableList().also { it[index] = value }

private fun <T> List<T>.moved(from: Int, to: Int): List<T> =
    toMutableList().also { it.add(to, it.removeAt(from)) }
