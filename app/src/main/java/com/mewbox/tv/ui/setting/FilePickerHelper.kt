package com.mewbox.tv.ui.setting

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat
import timber.log.Timber
import java.io.File

/**
 * 本地文件选择与存储权限管理工具。
 * 遵循现代 Android 存储规范，并兼容 Android 11+ 的 MANAGE_EXTERNAL_STORAGE。
 */
object FilePickerHelper {

    /** 检查是否已拥有读取本地文件权限。 */
    fun hasStoragePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * 发起权限请求：
     * - Android 11+ (API 30+)：跳转至“所有文件访问权限”系统设置页；
     * - Android 10 及以下：通过运行时权限请求 READ_EXTERNAL_STORAGE。
     */
    fun requestStoragePermission(
        context: Context,
        manageLauncher: ActivityResultLauncher<Intent>,
        permissionsLauncher: ActivityResultLauncher<Array<String>>,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                manageLauncher.launch(intent)
            } catch (_: Exception) {
                val fallbackIntent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                manageLauncher.launch(fallbackIntent)
            }
        } else {
            permissionsLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ),
            )
        }
    }

    /** 从 Content Uri 或最后路径段中提取文件名。 */
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
        }
        return name
    }

    /**
     * 将选中的 Uri 解析为持久可访问的本地路径：
     * 1. 优先解析为物理绝对路径并以 "file://" 形式返回；
     * 2. 若无法直接访问物理路径，则将其流复制至应用私有 subscriptions 目录并返回其 "file://" 路径。
     */
    fun resolveLocalFilePath(context: Context, uri: Uri): String {
        // 1. 尝试解析为物理绝对路径
        val realPath = getRealPathFromUri(context, uri)
        if (!realPath.isNullOrBlank()) {
            val file = File(realPath)
            if (file.exists() && file.canRead()) {
                return "file://${file.absolutePath}"
            }
        }

        // 2. 无法直接物理访问或位于虚拟卷：复制至应用私有 subscriptions 目录
        return try {
            val rawName = getFileName(context, uri)
            val mimeType = context.contentResolver.getType(uri).orEmpty()
            val ext = when {
                rawName.contains(".") -> ""
                mimeType.contains("html", ignoreCase = true) -> ".html"
                else -> ".json"
            }
            val fileName = rawName.ifBlank { "subscription_${System.currentTimeMillis()}" } + ext
            val dir = File(context.filesDir, "subscriptions").apply { if (!exists()) mkdirs() }
            val targetFile = File(dir, fileName)
            context.contentResolver.openInputStream(uri)?.use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            "file://${targetFile.absolutePath}"
        } catch (e: Exception) {
            Timber.e(e, "复制本地文件失败: %s", uri)
            // 兜底返回 Uri 字符串
            uri.toString()
        }
    }

    private fun getRealPathFromUri(context: Context, uri: Uri): String? {
        if (DocumentsContract.isDocumentUri(context, uri)) {
            val docId = DocumentsContract.getDocumentId(uri)
            if ("com.android.externalstorage.documents" == uri.authority) {
                val split = docId.split(":")
                val type = split.getOrNull(0).orEmpty()
                if (type.equals("primary", ignoreCase = true)) {
                    val rel = if (split.size > 1) split[1] else ""
                    return "${Environment.getExternalStorageDirectory()}/$rel"
                } else {
                    return "/storage/${docId.replace(":", "/")}"
                }
            } else if ("com.android.providers.downloads.documents" == uri.authority) {
                if (docId.startsWith("raw:")) {
                    return docId.removePrefix("raw:")
                }
                val fileName = getFileName(context, uri)
                if (fileName.isNotBlank()) {
                    val dlFile = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName)
                    if (dlFile.exists()) return dlFile.absolutePath
                }
            }
        } else if (ContentResolver.SCHEME_FILE.equals(uri.scheme, ignoreCase = true)) {
            return uri.path
        }
        return null
    }
}

