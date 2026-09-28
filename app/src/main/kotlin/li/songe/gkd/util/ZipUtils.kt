package li.songe.gkd.util

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object ZipUtils {
    private const val BUFFER_LEN = 8192
    private fun zipFile(
        srcFile: File,
        rawRootPath: String,
        zos: ZipOutputStream,
        comment: String?,
    ): Boolean {
        val rootPath =
            rawRootPath + (if (rawRootPath.isBlank()) "" else File.separator) + srcFile.getName()
        if (srcFile.isDirectory()) {
            val fileList = srcFile.listFiles()
            if (fileList == null || fileList.size <= 0) {
                val entry = ZipEntry("$rootPath/")
                entry.setComment(comment)
                zos.putNextEntry(entry)
                zos.closeEntry()
            } else {
                for (file in fileList) {
                    if (!zipFile(file, rootPath, zos, comment)) return false
                }
            }
        } else {
            var stream: InputStream? = null
            try {
                stream = BufferedInputStream(FileInputStream(srcFile))
                val entry = ZipEntry(rootPath)
                entry.setComment(comment)
                zos.putNextEntry(entry)
                val buffer: ByteArray? = ByteArray(BUFFER_LEN)
                var len: Int
                while ((stream.read(buffer, 0, BUFFER_LEN).also { len = it }) != -1) {
                    zos.write(buffer, 0, len)
                }
                zos.closeEntry()
            } finally {
                stream?.close()
            }
        }
        return true
    }

    fun zipFiles(srcFiles: Collection<File>, zipFile: File): Boolean {
        var zos: ZipOutputStream? = null
        try {
            zos = ZipOutputStream(FileOutputStream(zipFile))
            for (srcFile in srcFiles) {
                if (!zipFile(srcFile, "", zos, null)) return false
            }
            return true
        } finally {
            if (zos != null) {
                zos.finish()
                zos.close()
            }
        }
    }

    fun unzipFile(
        zipFile: File,
        destDir: File,
    ) {
        val destRoot = destDir.canonicalFile
        ZipFile(zipFile).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                val outFile = File(destRoot, entry.name)
                // 防 Zip Slip: 条目名里的 `..` / 绝对路径 / 符号链接都不能写到 destDir 之外。
                // (AndroidManifest.xml 里接收 zip 的 OpenFileActivity 是 exported=true,
                //  设备上任意 App 都能投递构造好的 zip —— 不校验就等于允许越目录写文件)
                if (!outFile.canonicalPath.startsWith(destRoot.path + File.separator)) {
                    LogUtils.d("unzip skip out-of-dir entry: ${entry.name}")
                    return@forEach
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(outFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
        }
    }
}