package net.lighttools.lightmux.ui.host

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.lighttools.lightmux.R
import net.lighttools.lightmux.data.Host
import net.lighttools.lightmux.data.SshKey
import net.lighttools.lightmux.ui.common.BackButton
import net.lighttools.lightmux.ui.common.ErrorBanner
import net.lighttools.lightmux.ui.keys.PickKeyFileButton
import net.lighttools.lightmux.ui.terminal.connectionFailureText
import net.lighttools.lightmux.ui.theme.Success

/**
 * 主机增删改表单。按「基本 / 认证方式 / 高级」分三张卡片，卡片内一行一项、行间细分隔线。
 *
 * 不用带边框的 [androidx.compose.material3.OutlinedTextField]：那东西每个字段自带 56dp 高外加
 * 一圈边框和浮动标签，八九个字段摞下来满屏都是框，还得滚两屏才看得到「登录后执行」。
 * 换成「标签在左、输入在右」之后，一屏基本能装下整张表，分组也才看得出来。
 *
 * 密码 / 私钥**不回显明文**：编辑已有主机时输入框留空并提示「已保存，留空表示不修改」，
 * 保存时由 [HostForm.toHost] 把旧凭据原样带回去。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostEditScreen(
    vm: HostEditViewModel,
    onDone: () -> Unit,
    onOpenKeys: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val form = vm.form
    val errors = vm.errors
    val isNew = form.id == null

    Scaffold(
        modifier = modifier.fillMaxSize().imePadding(),
        topBar = {
            // 测试结果吊在标题栏下面而不是表单里：表单要滚两屏，结论跟着滚走就等于没有结论。
            Column {
                TopAppBar(
                    title = {
                        Text(
                            stringResource(
                                if (isNew) R.string.host_edit_title_new else R.string.host_edit_title_edit
                            )
                        )
                    },
                    navigationIcon = { BackButton(onDone) },
                    actions = {
                        TextButton(onClick = vm::testConnection, enabled = vm.test != HostTest.Running) {
                            Text(
                                stringResource(
                                    if (vm.test == HostTest.Running) R.string.host_test_running
                                    else R.string.host_test
                                )
                            )
                        }
                        IconButton(onClick = { vm.save(onDone) }) {
                            Icon(Icons.Default.Check, stringResource(R.string.save))
                        }
                    },
                )
                TestResultBanner(vm.test)
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            FormSection(stringResource(R.string.form_section_basic)) {
                FieldRow(
                    label = stringResource(R.string.field_name),
                    value = form.name,
                    onValueChange = { v -> vm.update { it.copy(name = v) } },
                )
                FieldDivider()
                FieldRow(
                    label = stringResource(R.string.field_hostname),
                    value = form.hostname,
                    onValueChange = { v -> vm.update { it.copy(hostname = v) } },
                    error = HostFormError.Hostname in errors,
                )
                FieldDivider()
                // 端口和用户名并排：两个都短，各占一行等于白扔一行的高度。
                Row {
                    FieldRow(
                        label = stringResource(R.string.field_port),
                        value = form.port,
                        onValueChange = { v -> vm.update { it.copy(port = v) } },
                        error = HostFormError.Port in errors,
                        errorText = stringResource(R.string.error_port_invalid),
                        keyboardType = KeyboardType.Number,
                        labelWidth = NARROW_LABEL_WIDTH,
                        modifier = Modifier.weight(1f),
                    )
                    FieldRow(
                        label = stringResource(R.string.field_username),
                        value = form.username,
                        onValueChange = { v -> vm.update { it.copy(username = v) } },
                        error = HostFormError.Username in errors,
                        labelWidth = NARROW_LABEL_WIDTH,
                        modifier = Modifier.weight(1.3f),
                    )
                }
            }

            val keptHint = stringResource(R.string.secret_kept).takeIf { form.keepSecret }
            FormSection(stringResource(R.string.field_auth)) {
                ChipRow {
                    FilterChip(
                        selected = form.authKind == AuthKind.Password,
                        onClick = { vm.setAuthKind(AuthKind.Password) },
                        label = { Text(stringResource(R.string.auth_password)) },
                    )
                    FilterChip(
                        selected = form.authKind == AuthKind.PrivateKey,
                        onClick = { vm.setAuthKind(AuthKind.PrivateKey) },
                        label = { Text(stringResource(R.string.auth_key)) },
                    )
                    FilterChip(
                        selected = form.authKind == AuthKind.None,
                        onClick = { vm.setAuthKind(AuthKind.None) },
                        label = { Text(stringResource(R.string.auth_none)) },
                    )
                }
                when (form.authKind) {
                    AuthKind.Password -> {
                        FieldDivider()
                        FieldRow(
                            label = stringResource(R.string.auth_password),
                            value = form.password,
                            onValueChange = vm::setPassword,
                            error = HostFormError.Secret in errors,
                            errorText = stringResource(R.string.error_secret_required),
                            helper = keptHint,
                            secret = true,
                        )
                    }

                    AuthKind.PrivateKey -> {
                        // 选择器常驻（不再因为密钥库空着而藏掉）：它现在还背着「管理密钥」的入口，
                        // 空库时用户正是要从这里跳去导入——藏掉它就断了这条路，只剩一个粘贴框
                        // 的用户根本不知道这个 app 有密钥管理这回事。
                        FieldDivider()
                        KeySourcePicker(
                            keys = vm.keys,
                            selectedId = form.keyId,
                            onSelect = vm::selectKey,
                            onUsePasted = vm::usePastedKey,
                            onManage = onOpenKeys,
                        )
                        if (form.keyId == null) {
                            FieldDivider()
                            BlockField(
                                label = stringResource(R.string.field_pem),
                                value = form.pem,
                                onValueChange = vm::setPem,
                                error = HostFormError.Secret in errors,
                                errorText = stringResource(R.string.error_secret_required),
                                helper = keptHint,
                                monospace = true,
                                action = {
                                    PickKeyFileButton(
                                        onPicked = vm::loadPemFromFile,
                                        error = vm.keyFileError,
                                    )
                                },
                            )
                            FieldDivider()
                            FieldRow(
                                label = stringResource(R.string.field_passphrase),
                                value = form.passphrase,
                                onValueChange = vm::setPassphrase,
                                secret = true,
                            )
                        }
                    }

                    AuthKind.None -> {
                        FieldDivider()
                        Text(
                            text = stringResource(R.string.auth_none_hint),
                            modifier = Modifier.padding(horizontal = FIELD_PADDING_H, vertical = 12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            FormSection(stringResource(R.string.form_section_advanced)) {
                // 「登录后执行」这类长标签走块式：塞进左栏的话，英文文案要折成两行。
                BlockField(
                    label = stringResource(R.string.field_login_command),
                    value = form.loginCommand,
                    onValueChange = { v -> vm.update { it.copy(loginCommand = v) } },
                    monospace = true,
                    singleLine = true,
                )
                // 一台主机都没有时不摆一个只有「直连」一项的选择器；但引用的跳板机被删了必须显示。
                if (vm.jumpCandidates.isNotEmpty() || form.proxyJumpId != null) {
                    FieldDivider()
                    ProxyJumpPicker(
                        candidates = vm.jumpCandidates,
                        selectedId = form.proxyJumpId,
                        onSelect = vm::setProxyJump,
                    )
                }
            }
        }
    }
}

/**
 * 「测试」的结论条。
 *
 * 失败复用终端页那套分类文案（[connectionFailureText]）：同一个失败在两处说法不一样，
 * 用户会以为碰到的是两回事。成功时把主机公钥指纹一并摆出来——首次连接是 TOFU 静默记下的，
 * 这里是用户唯一一次能拿它和 `ssh-keyscan` 对照的机会。
 */
@Composable
private fun TestResultBanner(test: HostTest) = when (test) {
    HostTest.Idle -> Unit

    // 握手最长要 20 秒，只把按钮置灰的话这段时间里页面看着就是死的
    HostTest.Running -> LinearProgressIndicator(Modifier.fillMaxWidth())

    is HostTest.Ok -> Surface(
        modifier = Modifier.fillMaxWidth(),
        // 成功必须是绿的，见 [Success]
        color = Success,
        contentColor = Color.White,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(stringResource(R.string.host_test_ok), style = MaterialTheme.typography.bodyMedium)
            test.fingerprint?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }

    is HostTest.Failed -> ErrorBanner(connectionFailureText(test.failure, test.endpoint))
}

/**
 * 一组字段：小标题 + 一张圆角卡片。
 *
 * 卡片底色用 `surfaceContainer`（比页面背景深一档），不加阴影也不描边——
 * 这个 app 别的页面全是扁平列表，卡片一旦浮起来就跟别处不是一个东西了。
 */
@Composable
private fun FormSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            modifier = Modifier.padding(start = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            content = { Column(content = content) },
        )
    }
}

/** 行间分隔线。左端与标签对齐、右端到底，是列表式表单的常规画法。 */
@Composable
private fun FieldDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = FIELD_PADDING_H),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/** 卡片里的一行 chip。上下留白比字段行小一点：chip 自己就有 32dp 高。 */
@Composable
private fun ChipRow(content: @Composable RowScope.() -> Unit) = Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = FIELD_PADDING_H, vertical = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    content = content,
)

/**
 * 「标签在左、输入在右」的一行。错误 / 提示文案吊在输入区下面，和标签左对齐。
 *
 * 输入用 [BasicTextField] 而不是 M3 的 TextField：后者的装饰盒自带 56dp 高和一圈容器色，
 * 摆进卡片里就是「框中框」。这里要的只是一个能打字的地方，边界由行和分隔线交代。
 */
@Composable
private fun FieldRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    error: Boolean = false,
    errorText: String? = null,
    helper: String? = null,
    secret: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    labelWidth: Dp = LABEL_WIDTH,
) {
    val requiredText = stringResource(R.string.error_field_required)
    Column(modifier = modifier.padding(horizontal = FIELD_PADDING_H, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                modifier = Modifier.width(labelWidth),
                style = MaterialTheme.typography.bodyMedium,
                color = if (error) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
            Input(
                value = value,
                onValueChange = onValueChange,
                secret = secret,
                keyboardType = keyboardType,
                modifier = Modifier.weight(1f),
            )
        }
        FieldHint(
            error = error,
            text = if (error) errorText ?: requiredText else helper,
            modifier = Modifier.padding(start = labelWidth),
        )
    }
}

/**
 * 标签在上、输入在下的一块。给长标签（私钥 PEM、登录后执行）和多行输入用。
 *
 * @param action 标签行右侧的附加按钮，目前只有「从文件选择」
 */
@Composable
private fun BlockField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    error: Boolean = false,
    errorText: String? = null,
    helper: String? = null,
    monospace: Boolean = false,
    singleLine: Boolean = false,
    action: (@Composable () -> Unit)? = null,
) {
    val requiredText = stringResource(R.string.error_field_required)
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = FIELD_PADDING_H, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (error) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            action?.invoke()
        }
        if (singleLine) {
            Input(
                value = value,
                onValueChange = onValueChange,
                monospace = monospace,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            // 多行输入垫一层浅底：四行高的纯空白没有任何边界，用户看不出这里是能贴东西的。
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
            ) {
                Input(
                    value = value,
                    onValueChange = onValueChange,
                    monospace = monospace,
                    singleLine = false,
                    // PEM 是几十行的东西，给个下限撑出「这里要贴一大段」的样子；再高就把口令挤下去了。
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                )
            }
        }
        FieldHint(error = error, text = if (error) errorText ?: requiredText else helper)
    }
}

/** 错误红字 / 灰色提示。两者不会同时出现——报错的时候提示已经不重要了。 */
@Composable
private fun FieldHint(error: Boolean, text: String?, modifier: Modifier = Modifier) {
    if (text == null) return
    Text(
        text = text,
        modifier = modifier.padding(top = 2.dp),
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 裸输入框。颜色必须显式给：[BasicTextField] 默认拿 `Color.Unspecified`（=黑字），
 * 深色主题下就成了黑底黑字。
 */
@Composable
private fun Input(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    secret: Boolean = false,
    monospace: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        // 等宽的那几项（PEM、登录命令）用小一号字：16sp 的等宽字一行装不下几个字符，
        // 一段 base64 能折成十几行。
        textStyle = (
            if (monospace) MaterialTheme.typography.bodyMedium
            else MaterialTheme.typography.bodyLarge
            ).copy(
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = if (monospace) FontFamily.Monospace else null,
        ),
        singleLine = singleLine,
        minLines = minLines,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
    )
}

/**
 * 跳板机（`ssh -J`）：从**已有主机**里挑一台，先连它再从那儿钻过去。
 *
 * 只列主机、不另开一套地址与凭据输入框，理由见 [net.lighttools.lightmux.data.HostRoute]。
 * 形态与 [KeySourcePicker] 一致（平铺 chip）——同一张表单里两个「从已有的里挑一个」，
 * 长得不一样只会让人以为它们的规矩不同。
 *
 * 会绕成环的主机在 [candidates] 里就没有了，所以这里不需要任何校验。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProxyJumpPicker(
    candidates: List<Host>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = FIELD_PADDING_H, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(R.string.field_proxy_jump),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = selectedId == null,
                onClick = { onSelect(null) },
                label = { Text(stringResource(R.string.proxy_jump_none)) },
            )
            candidates.forEach { candidate ->
                FilterChip(
                    selected = candidate.id == selectedId,
                    onClick = { onSelect(candidate.id) },
                    label = { Text(candidate.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
            // 跳板机被删了：这台主机现在**连不上**（不会退化成直连，那样只会撞出一条超时），
            // 必须说出来，否则用户对着一张看起来正常的表单查不出问题在哪。
            if (selectedId != null && candidates.none { it.id == selectedId }) {
                FilterChip(
                    selected = true,
                    onClick = {},
                    label = { Text(stringResource(R.string.proxy_jump_missing)) },
                )
            }
        }
        if (selectedId != null) {
            Text(
                stringResource(R.string.proxy_jump_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 左栏标签宽度。中文四字、英文 "Key passphrase" 都按这个宽度对齐。 */
private val LABEL_WIDTH = 96.dp

/** 端口 / 用户名并排时的标签宽度——那一行两个标签都只有两三个字。 */
private val NARROW_LABEL_WIDTH = 44.dp

private val FIELD_PADDING_H = 14.dp

/**
 * 私钥来源：手工粘贴，或者密钥库里的某一把。
 *
 * 密钥库里的钥匙**平铺成一排 chip**，不藏在下拉框里：藏起来的话，按钮上只写着「直接粘贴」，
 * 用户看不出这台设备上早就导过钥匙，只会又粘一遍。
 * 选了库里的钥匙，表单里就不再出现任何私钥输入框——一台主机只能有一个 PEM 的来源，
 * 同时摆两处会让人搞不清最终连的是哪一份。
 *
 * 标题行右侧常驻「管理密钥」入口：表单里挑不到想要的钥匙时跳去密钥管理导入，
 * 回来后钥匙列表跟着 Flow 自动长出来（见 [HostEditViewModel.keys]），不用重建页面。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeySourcePicker(
    keys: List<SshKey>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onUsePasted: () -> Unit,
    onManage: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = FIELD_PADDING_H, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.field_key_source),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = onManage,
                // 默认内容边距左右各 24dp，「管理密钥」四个字能撑到小半行宽，压到跟标签行一个量级
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(stringResource(R.string.key_source_manage))
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = selectedId == null,
                onClick = onUsePasted,
                label = { Text(stringResource(R.string.key_source_paste)) },
            )
            keys.forEach { key ->
                FilterChip(
                    selected = key.id == selectedId,
                    onClick = { onSelect(key.id) },
                    label = { Text(key.name) },
                )
            }
            // 钥匙没了：这里必须说实话，不能退回「直接粘贴」装作没事——
            // 表单看着正常、连接却一直失败是最难查的一种。
            if (selectedId != null && keys.none { it.id == selectedId }) {
                FilterChip(
                    selected = true,
                    onClick = {},
                    label = { Text(stringResource(R.string.key_source_missing)) },
                )
            }
        }
        if (selectedId != null) {
            Text(
                stringResource(R.string.key_source_managed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
