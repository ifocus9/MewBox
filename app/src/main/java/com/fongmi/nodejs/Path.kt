package com.fongmi.nodejs

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets

object Path {
    @JvmStatic
    @Throws(IOException::class)
    fun writeAtomically(target: File, data: ByteArray) {
        writeAtomically(target, ByteArrayInputStream(data))
    }

    @JvmStatic
    @Throws(IOException::class)
    fun writeAtomically(target: File, source: InputStream) {
        val parent = target.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
            throw IOException("Unable to create directory: $parent")
        }
        val temp = File.createTempFile("tmp-", ".tmp", parent)
        try {
            FileOutputStream(temp).use { output ->
                val buffer = ByteArray(16 * 1024)
                var count: Int
                while (source.read(buffer).also { count = it } != -1) {
                    output.write(buffer, 0, count)
                }
                output.flush()
                output.fd.sync()
            }
            if (target.exists() && !target.delete()) {
                throw IOException("Unable to delete existing file: $target")
            }
            if (!temp.renameTo(target)) {
                copyFile(temp, target)
                temp.delete()
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun read(file: File): String {
        FileInputStream(file).use { input ->
            ByteArrayOutputStream().use { output ->
                val buffer = ByteArray(16 * 1024)
                var count: Int
                while (input.read(buffer).also { count = it } != -1) {
                    output.write(buffer, 0, count)
                }
                return output.toString(StandardCharsets.UTF_8.name())
            }
        }
    }

    @JvmStatic
    fun clear(file: File?) {
        if (file == null || !file.exists()) return
        if (file.isDirectory) {
            file.listFiles()?.forEach { clear(it) }
        }
        file.delete()
    }

    private fun copyFile(inStream: File, outStream: File) {
        FileInputStream(inStream).use { input ->
            FileOutputStream(outStream).use { output ->
                val buffer = ByteArray(16 * 1024)
                var count: Int
                while (input.read(buffer).also { count = it } != -1) {
                    output.write(buffer, 0, count)
                }
                output.flush()
                output.fd.sync()
            }
        }
    }
}

