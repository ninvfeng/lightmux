package net.lighttools.lightmux.ui.keys

import android.content.ContentResolver
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.lighttools.lightmux.R

/** 从文件选私钥失败的原因。每一种都对应一句能让用户知道下一步干什么的提示。 */
enum class KeyFileError { Unreadable, NotAKey, Putty }

/**
 * 「从文件选择」按钮。主机表单和密钥库两处都用，所以放在这里而不是各写一份。
 *
 * MIME 不设限：PEM 没有公认的类型，限死了反而在文件选择器里挑不着。
 */
@Composable
fun PickKeyFileButton(
    onPicked: (ContentResolver, Uri) -> Unit,
    error: KeyFileError?,
    modifier: Modifier = Modifier,
) {
    val resolver = LocalContext.current.contentResolver
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { onPicked(resolver, it) }
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) {
            Text(stringResource(R.string.key_pick_file))
        }
        if (error != null) {
            Text(
                stringResource(
                    when (error) {
                        KeyFileError.Unreadable -> R.string.error_key_file_unreadable
                        KeyFileError.NotAKey -> R.string.error_key_file_not_a_key
                        KeyFileError.Putty -> R.string.error_key_file_putty
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
