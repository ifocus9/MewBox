package com.mewbox.tv.ui.setting

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContracts
import timber.log.Timber
import java.io.File

/**
 * 本地文件选择器辅助工具：
 * 仅依赖系统 SAF（Storage Access Framework）直读选中文档流，无需任何外部存储权限。
 */
object FilePickerHelper {

    /** 面向所有文档类型的系统 SAF 选择契约，默认尝试定位到主内部存储根目录。 */
    class OpenStorageDocument : ActivityResultContracts.OpenDocument() {
        override fun createIntent(context: Context, input: Array<String>): Intent {
            return super.createIntent(context, input).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    putExtra(
                        DocumentsContract.EXTRA_INITIAL_URI,
                        Uri.parse("content://com.android.externalstorage.documents/root/primary"),
                    )
                }
            }
        }
    }

    /** 提取 SAF Uri 对应的用户可读文件名。 */
    fun getFileName(context: Context, uri: Uri): String {
        var name = ""
        if (uri.scheme.equals(ContentResolver.SCHEME_CONTENT, ignoreCase = true)) {
            val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
            try {
                context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val colIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (colIndex >= 0) {
                            name = cursor.getString(colIndex).orEmpty()
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "获取文件名失败: %s", uri)
            }
        }
        if (name.isBlank()) {
            name = uri.lastPathSegment.orEmpty()
                .substringAfterLast('/')
                .substringAfterLast(':')
        }
        return name.trim()
    }

    /** 判断配置地址是否指向应用托管的内部本地订阅（编辑弹窗据此隐藏底层私有路径）。 */
    fun isInternalLocalConfigUrl(context: Context, url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.startsWith("local://", ignoreCase = true)) return true
        if (!trimmed.startsWith("file://", ignoreCase = true)) return false
        val path = Uri.parse(trimmed).path ?: return false
        val fileCanonical = runCatching { File(path).canonicalPath }.getOrNull() ?: return false
        val managedDirs = listOf("local_html", "local_configs", "subscriptions")
        return managedDirs.any { dirName ->
            val dirCanonical = runCatching { File(context.filesDir, dirName).canonicalPath }.getOrNull()
                ?: return@any false
            fileCanonical == dirCanonical || fileCanonical.startsWith("$dirCanonical${File.separator}")
        }
    }
}
