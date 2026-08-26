package net.lighttools.lightmux.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.lighttools.lightmux.R
import net.lighttools.lightmux.ui.common.BackButton
import net.lighttools.lightmux.update.Repo

/**
 * 关于页。
 *
 * 这里的第三方声明**不是装饰**：vendored 的 `terminal-emulator` / `terminal-view` 是 Apache-2.0，
 * 该许可第 4(d) 条要求分发衍生作品时随附 NOTICE 内容。APK 里没有 NOTICE 文件，
 * 所以本页就是履行这条义务的地方——只放一个外链不算数（用户离线时看不到）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    version: String,
    onBack: () -> Unit,
    onOpenProject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_about)) },
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.app_name),
                modifier = Modifier.padding(top = 16.dp),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = stringResource(R.string.about_version, version),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.about_tagline),
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onOpenProject, modifier = Modifier.padding(top = 8.dp)) {
                Text(Repo.HOME_URL)
            }

            Section(
                title = stringResource(R.string.about_license_title),
                body = stringResource(R.string.about_license_body),
            )
            Section(
                title = stringResource(R.string.about_notice_title),
                body = stringResource(R.string.about_notice_body),
            )
            Section(
                title = stringResource(R.string.about_dependencies_title),
                body = stringResource(R.string.about_dependencies_body),
            )
        }
    }
}

@Composable
private fun Section(title: String, body: String) {
    HorizontalDivider(Modifier.padding(top = 16.dp))
    Text(
        text = title,
        modifier = Modifier.padding(top = 16.dp),
        style = MaterialTheme.typography.titleSmall,
    )
    Text(
        text = body,
        modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
