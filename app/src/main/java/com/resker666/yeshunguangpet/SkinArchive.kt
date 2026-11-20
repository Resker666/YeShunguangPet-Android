package com.resker666.yeshunguangpet

import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.zip.CRC32

internal const val MAX_ARCHIVE_BYTES = 80L * 1024 * 1024
private const val MAX_MANIFEST_BYTES = 128L * 1024
private const val MAX_PNG_BYTES = 64L * 1024 * 1024

internal object SkinArchive {
    fun read(archive: File, manifestFile: File, spriteFile: File): SkinManifest {
        checkFormat(archive.length() in 1..MAX_ARCHIVE_BYTES, "ZIP 文件大小超出限制")
        ZipFile.builder().setFile(archive).get().use { zip ->
            val names = HashSet<String>()
            val entries = zip.entries
            var count = 0
            var total = 0L
            var manifestPath: String? = null
            val files = linkedMapOf<String, org.apache.commons.compress.archivers.zip.ZipArchiveEntry>()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val path = entry.name.removeSuffix("/")
                checkFormat(++count <= 256, "ZIP 条目超过 256 个")
                checkFormat(
                    '\\' !in path && ':' !in path && path.none(Char::isISOControl) &&
                        path.split('/').none { it.isEmpty() || it == "." || it == ".." },
                    "ZIP 中存在无效的条目路径",
                )
                checkFormat(names.add(path.lowercase(Locale.ROOT)), "ZIP 中存在重复路径")
                checkFormat(!entry.isUnixSymlink, "ZIP 中包含符号链接")
                checkFormat(entry.size in 0..(MAX_ARCHIVE_BYTES - total), "ZIP 解压后的大小超出限制")
                total += entry.size
                if (entry.isDirectory) continue
                checkFormat(zip.canReadEntryData(entry), "ZIP 条目使用了不支持的格式")
                files[path] = entry
                if (path.substringAfterLast('/') == "pet.json") {
                    checkFormat(manifestPath == null, "ZIP 必须只包含一个 pet.json")
                    manifestPath = path
                }
            }
            val path = manifestPath ?: throw SkinFormatException("ZIP 中找不到 pet.json")
            val entry = files.getValue(path)
            copyEntry(zip, entry, manifestFile, MAX_MANIFEST_BYTES)
            val manifest = manifestFile.inputStream().use(::readManifest)
            val directory = path.substringBeforeLast('/', "")
            val spritePath = if (directory.isEmpty()) manifest.spriteSheet else "$directory/${manifest.spriteSheet}"
            val sprite = files[spritePath] ?: throw SkinFormatException("ZIP 中找不到精灵图 ${manifest.spriteSheet}")
            copyEntry(zip, sprite, spriteFile, MAX_PNG_BYTES)
            spriteFile.inputStream().use { verifyPngHeader(it, manifest) }
            return manifest
        }
    }

    private fun copyEntry(
        zip: ZipFile,
        entry: org.apache.commons.compress.archivers.zip.ZipArchiveEntry,
        output: File,
        limit: Long,
    ) {
        checkFormat(entry.size in 1..limit, "皮肤文件大小超出限制")
        val checksum = CRC32()
        val copied = zip.getInputStream(entry).use { input ->
            output.outputStream().buffered().use { boundedCopy(input, it, entry.size, checksum) }
        }
        checkFormat(copied == entry.size && checksum.value == entry.crc, "ZIP 条目内容损坏")
    }
}

internal fun readManifest(input: InputStream): SkinManifest {
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
    return SkinManifest.parse(java.io.InputStreamReader(input, decoder))
}

internal fun verifyPngHeader(input: InputStream, manifest: SkinManifest) {
    val data = DataInputStream(input)
    val signature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    val found = ByteArray(signature.size)
    data.readFully(found)
    checkFormat(found.contentEquals(signature) && data.readInt() == 13 &&
        data.readInt() == 0x49484452, "精灵图必须是 PNG 图片")
    checkFormat(data.readInt() == manifest.width && data.readInt() == manifest.height,
        "PNG 尺寸与清单中的精灵图网格不匹配")
}

internal fun boundedCopy(input: InputStream, output: OutputStream, limit: Long, checksum: CRC32? = null): Long {
    val buffer = ByteArray(8192)
    var copied = 0L
    while (true) {
        val count = input.read(buffer)
        if (count < 0) return copied
        checkFormat(count <= limit - copied, "皮肤文件大小超出限制")
        output.write(buffer, 0, count)
        checksum?.update(buffer, 0, count)
        copied += count
    }
}
