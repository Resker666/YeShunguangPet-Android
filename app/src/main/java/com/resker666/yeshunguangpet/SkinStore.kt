package com.resker666.yeshunguangpet

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.Executors

internal class PetApplication : Application() {
    val store by lazy { SkinStore(this) }
    val worker = Executors.newSingleThreadExecutor()
    val overlayListeners = mutableSetOf<() -> Unit>()
    var overlayRunning = false
        set(value) {
            if (field == value) return
            field = value
            overlayListeners.toList().forEach { it() }
        }
}

internal data class Skin(val manifest: SkinManifest, val directory: File? = null)
internal data class Sprite(val skin: Skin, val bitmap: Bitmap)
internal data class ImportedSkin(val skin: Skin, val alreadyInstalled: Boolean)

internal class SkinStore(private val context: Context) {
    private val root = File(context.filesDir, "skins").apply { mkdirs() }
    val prefs = context.getSharedPreferences("pet", Context.MODE_PRIVATE)
    private val starter by lazy {
        Skin(context.assets.open("starter/pet.json").use(::readManifest))
    }
    private var cached: Sprite? = null

    fun list(): List<Skin> = listOf(starter) + root.listFiles().orEmpty()
        .filter { it.isDirectory && !it.name.startsWith(".") }
        .mapNotNull { directory ->
            runCatching {
                val manifest = File(directory, "pet.json").inputStream().use(::readManifest)
                checkFormat(manifest.id == directory.name && File(directory, "sprite.png").isFile,
                    "皮肤文件缺失")
                Skin(manifest, directory)
            }.getOrNull()
        }.sortedBy { it.manifest.name }

    fun selected(): Skin = list().firstOrNull { it.manifest.id == prefs.getString(SELECTED_ID, null) } ?: starter

    fun selectedSprite(): Sprite = runCatching { sprite(selected()) }.getOrElse { sprite(starter) }

    fun select(skin: Skin) {
        prefs.edit().putString(SELECTED_ID, skin.manifest.id).apply()
    }

    fun sprite(skin: Skin): Sprite {
        cached?.takeIf { it.skin.manifest.id == skin.manifest.id }?.let { return it }
        val options = BitmapFactory.Options().apply { inScaled = false }
        val bitmap = skin.directory?.let { BitmapFactory.decodeFile(File(it, "sprite.png").path, options) }
            ?: if (skin.directory == null) {
                context.assets.open("starter/sprite.png").use { BitmapFactory.decodeStream(it, null, options) }
            } else null
        val decoded = bitmap ?: throw SkinFormatException("无法读取皮肤精灵图")
        return Sprite(skin, decoded).also { cached = it }
    }

    fun import(uri: Uri): ImportedSkin {
        val archive = File.createTempFile("skin-", ".zip", context.cacheDir)
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw SkinFormatException("无法读取所选文件")
            input.use {
                archive.outputStream().buffered().use { output -> boundedCopy(it, output, MAX_ARCHIVE_BYTES) }
            }
            val staging = Files.createTempDirectory(root.toPath(), ".import-").toFile()
            try {
                val manifestFile = File(staging, "pet.json")
                val spriteFile = File(staging, "sprite.png")
                val manifest = SkinArchive.read(archive, manifestFile, spriteFile)
                checkFormat(manifest.id != starter.manifest.id, "该皮肤 ID 已被内置角色使用")
                val target = File(root, manifest.id)
                if (target.exists()) {
                    val same = sameContent(manifestFile, File(target, "pet.json")) &&
                        sameContent(spriteFile, File(target, "sprite.png"))
                    checkFormat(same, "已有同 ID 的皮肤，现有皮肤已保留")
                    return ImportedSkin(Skin(manifest, target), alreadyInstalled = true)
                }
                verifyDecodable(spriteFile, manifest)
                check(staging.renameTo(target)) { "无法保存皮肤" }
                return ImportedSkin(Skin(manifest, target), alreadyInstalled = false)
            } finally {
                staging.deleteRecursively()
            }
        } finally {
            archive.delete()
        }
    }

    private fun verifyDecodable(file: File, manifest: SkinManifest) {
        val bitmap = BitmapFactory.decodeFile(file.path)
            ?: throw SkinFormatException("PNG 图片无法解码")
        try {
            checkFormat(bitmap.width == manifest.width && bitmap.height == manifest.height,
                "PNG 图片尺寸不符合皮肤清单")
        } finally {
            bitmap.recycle()
        }
    }

    private fun sameContent(first: File, second: File): Boolean =
        second.isFile && first.length() == second.length() && digest(first).contentEquals(digest(second))

    private fun digest(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest()
    }
    companion object {
        const val SELECTED_ID = "selected"
    }
}
