package net.lighttools.lightmux.ui.common

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast

/**
 * 复制一段文本，并在需要时说一声。
 *
 * 反馈按系统版本分：Android 13 起系统自己会在屏幕左下角弹一张剪贴板预览，
 * 这时再 Toast 一句就成了**两条**提示；13 以下没有任何系统反馈，不吭声用户不知道复制成没成。
 *
 * 拿不到剪贴板服务时静默返回：这只在极精简 ROM 上发生，为一次「复制路径」崩掉整个 app 不值当。
 */
fun copyToClipboard(context: Context, text: String, confirmation: String) {
    val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clip.setPrimaryClip(ClipData.newPlainText("lightmux", text))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, confirmation, Toast.LENGTH_SHORT).show()
    }
}
