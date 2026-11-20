package com.resker666.yeshunguangpet

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

class SkinArchiveTest {
    @get:Rule val temp = TemporaryFolder()

    private val valid = """
        {
          "schemaVersion": 1, "id": "example", "name": "示例",
          "cellWidth": 32, "cellHeight": 24, "columns": 2, "rows": 1,
          "animations": {"idle":{"durationsMs":[100,200],"loop":true}}
        }
    """.trimIndent()

    @Test fun readsRootAndFolderArchives() {
        for (prefix in listOf("", "example/")) {
            val skin = read(zip("${prefix}pet.json" to valid.toByteArray(), "${prefix}spritesheet.png" to png()))
            assertEquals("example", skin.id)
            assertEquals(64, skin.width)
            assertEquals(listOf(100, 200), skin.animations.getValue("idle").durationsMs)
        }
    }

    @Test fun readsUtf8BomAndOptionalWindowsFields() {
        val optional = valid.replace(
            "\"animations\"",
            """"lookDirections":[], "behavior":{"idleActions":[]}, "speech":{"click":["我在。"]}, "animations"""",
        )
        val skin = read(zip("pet.json" to ("\uFEFF$optional").toByteArray(), "spritesheet.png" to png()))
        assertEquals(listOf("我在。"), skin.clickLines)
    }

    @Test fun rejectsMismatchedPngDimensions() {
        rejected(zip("pet.json" to valid.toByteArray(), "spritesheet.png" to png(width = 32)))
    }

    @Test fun rejectsInvalidPng() {
        assertThrows(Exception::class.java) {
            read(zip("pet.json" to valid.toByteArray(), "spritesheet.png" to ByteArray(40)))
        }
    }

    @Test fun rejectsTraversalPathsAndCaseInsensitiveDuplicates() {
        val manifest = "pet.json" to valid.toByteArray()
        val sprite = "spritesheet.png" to png()
        rejected(zip(manifest, sprite, "../other.txt" to byteArrayOf(0)))
        rejected(zip(manifest, sprite, "SPRITESHEET.PNG" to png()))
        rejected(zip(manifest, sprite, "second/pet.json" to valid.toByteArray()))
    }

    @Test fun rejectsSymlinkEntries() {
        val archive = temp.newFile()
        ZipArchiveOutputStream(archive).use { output ->
            add(output, "pet.json", valid.toByteArray())
            add(output, "spritesheet.png", png())
            val link = ZipArchiveEntry("link").apply { unixMode = 0xA1FF }
            output.putArchiveEntry(link)
            output.write("spritesheet.png".toByteArray())
            output.closeArchiveEntry()
        }
        rejected(archive)
    }

    @Test fun rejectsUnknownAndDuplicateFieldsAndOptionalActionOutsideSheet() {
        for (invalid in listOf(
            valid.replace("\"schemaVersion\": 1", "\"unknown\": true, \"schemaVersion\": 1"),
            valid.replace("\"schemaVersion\": 1", "\"schemaVersion\": 1, \"schemaVersion\": 1"),
            valid.replace("\"durationsMs\":[100,200]", "\"startColumn\":1,\"durationsMs\":[100,200]"),
        )) {
            rejected(zip("pet.json" to invalid.toByteArray(), "spritesheet.png" to png()))
        }
    }

    @Test fun rejectsArchivesWithTooManyEntries() {
        val resources = mutableListOf("pet.json" to valid.toByteArray(), "spritesheet.png" to png())
        repeat(255) { resources.add("extra-$it" to byteArrayOf(0)) }
        rejected(zip(*resources.toTypedArray()))
    }

    @Test fun readsTheCommunityHutaoReleaseWhenSupplied() {
        val path = System.getenv("HUTAO_COMPAT_ZIP")
        assumeNotNull(path)
        val sprite = temp.newFile()
        val manifest = SkinArchive.read(File(requireNotNull(path)), temp.newFile(), sprite)
        assertEquals("hutao", manifest.id)
        assertEquals(1536, manifest.width)
        assertEquals(2288, manifest.height)
        assertEquals(setOf("idle", "waving", "jumping"), manifest.animations.keys.intersect(
            setOf("idle", "waving", "jumping"),
        ))
    }

    @Test fun animationUsesFrameDurationsAndStopsOneShots() {
        val idle = Animation(0, 0, listOf(100, 250), loop = true)
        assertEquals(FramePosition(0, 1), idle.frameAt(99))
        assertEquals(FramePosition(1, 250), idle.frameAt(100))
        assertEquals(FramePosition(0, 90), idle.frameAt(710))
        assertNull(idle.copy(loop = false).frameAt(350))
    }

    private fun read(file: File): SkinManifest = SkinArchive.read(file, temp.newFile(), temp.newFile())

    private fun rejected(file: File) {
        assertThrows(SkinFormatException::class.java) { read(file) }
    }

    private fun png(width: Int = 64): ByteArray {
        val png = ByteArrayOutputStream()
        val data = DataOutputStream(png)
        data.write(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
        val header = ByteArrayOutputStream()
        DataOutputStream(header).use {
            it.writeInt(width)
            it.writeInt(24)
            it.write(byteArrayOf(8, 6, 0, 0, 0))
        }
        chunk(data, "IHDR", header.toByteArray())
        val pixels = ByteArrayOutputStream()
        DeflaterOutputStream(pixels).use { it.write(ByteArray((width * 4 + 1) * 24)) }
        chunk(data, "IDAT", pixels.toByteArray())
        chunk(data, "IEND", byteArrayOf())
        return png.toByteArray()
    }

    private fun chunk(output: DataOutputStream, type: String, content: ByteArray) {
        val name = type.toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply {
            update(name)
            update(content)
        }
        output.writeInt(content.size)
        output.write(name)
        output.write(content)
        output.writeInt(crc.value.toInt())
    }

    private fun zip(vararg files: Pair<String, ByteArray>): File = temp.newFile().also { archive ->
        ZipArchiveOutputStream(archive).use { output -> files.forEach { (name, content) -> add(output, name, content) } }
    }

    private fun add(zip: ZipArchiveOutputStream, name: String, content: ByteArray) {
        zip.putArchiveEntry(ZipArchiveEntry(name))
        zip.write(content)
        zip.closeArchiveEntry()
    }
}
