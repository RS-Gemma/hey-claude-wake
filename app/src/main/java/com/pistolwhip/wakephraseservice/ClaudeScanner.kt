package com.pistolwhip.wakephraseservice

import android.content.Context
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/**
 * Lists internal strings of the installed assistant app that look like voice-mode switches,
 * deep links, or assistant entry points. Used to discover how to open voice mode directly.
 */
object ClaudeScanner {
    private val interesting = Regex("(?i)voice|claude://|claude\\.ai/|deeplink|deep_link|overlay|assist|dictat|speech|talk")
    private const val MAX_LINES = 600

    fun scan(context: Context): String {
        val pkg = AssistantLauncher.assistantPackage(context)
        val info = context.packageManager.getApplicationInfo(pkg, 0)
        val apks = listOf(info.sourceDir) + info.splitSourceDirs.orEmpty()
        val manifestHits = sortedSetOf<String>()
        val codeHits = sortedSetOf<String>()
        val resourceHits = sortedSetOf<String>()

        for (path in apks) {
            ZipFile(path).use { zip ->
                for (entry in zip.entries()) {
                    val name = entry.name
                    when {
                        name == "AndroidManifest.xml" ->
                            zip.getInputStream(entry).use { manifestStrings(it.readAll()) }
                                .filter { keep(it) || it.startsWith("/") }
                                .forEach { manifestHits.add(it) }
                        // Static launcher shortcuts (e.g. "Start voice") live in res/ XML files.
                        name.startsWith("res/") && name.endsWith(".xml") ->
                            zip.getInputStream(entry).use { manifestStrings(it.readAll()) }
                                .filter { keep(it) }
                                .forEach { resourceHits.add("$it   [$name]") }
                        name.matches(Regex("classes\\d*\\.dex")) ->
                            zip.getInputStream(entry).use { dexStrings(it.readAll()) }
                                .filter { keep(it) }
                                .forEach { codeHits.add(it) }
                    }
                }
            }
        }

        return buildString {
            append("Package: $pkg\nAPK files: ${apks.size}\n\n== Manifest (${manifestHits.size}) ==\n")
            manifestHits.forEach { append(it).append('\n') }
            append("\n== Resource XML (${resourceHits.size}) ==\n")
            resourceHits.forEach { append(it).append('\n') }
            append("\n== Code (${codeHits.size}")
            if (codeHits.size > MAX_LINES) append(", first $MAX_LINES shown")
            append(") ==\n")
            codeHits.take(MAX_LINES).forEach { append(it).append('\n') }
        }
    }

    private fun keep(s: String): Boolean =
        s.length in 3..120 && s.none { it.isWhitespace() } && interesting.containsMatchIn(s)

    private fun InputStream.readAll(): ByteArray = readBytes()

    /** Reads the string table of a .dex file. */
    private fun dexStrings(dex: ByteArray): List<String> {
        val buf = ByteBuffer.wrap(dex).order(ByteOrder.LITTLE_ENDIAN)
        val count = buf.getInt(0x38)
        val idsOff = buf.getInt(0x3C)
        val out = ArrayList<String>(count)
        for (i in 0 until count) {
            var pos = buf.getInt(idsOff + i * 4)
            // Skip the ULEB128 UTF-16 length prefix.
            while (dex[pos].toInt() and 0x80 != 0) pos++
            pos++
            val start = pos
            while (dex[pos].toInt() != 0) pos++
            out.add(String(dex, start, pos - start, Charsets.UTF_8))
        }
        return out
    }

    /** Reads the string pool of a binary AndroidManifest.xml. */
    private fun manifestStrings(xml: ByteArray): List<String> {
        val buf = ByteBuffer.wrap(xml).order(ByteOrder.LITTLE_ENDIAN)
        val pool = 8 // String pool chunk follows the 8-byte file header.
        if (xml.size < 32 || buf.getShort(pool).toInt() != 0x0001) return emptyList()
        val headerSize = buf.getShort(pool + 2).toInt()
        val count = buf.getInt(pool + 8)
        val utf8 = buf.getInt(pool + 16) and 0x100 != 0
        val stringsStart = pool + buf.getInt(pool + 20)
        val out = ArrayList<String>(count)
        for (i in 0 until count) {
            var pos = stringsStart + buf.getInt(pool + headerSize + i * 4)
            if (utf8) {
                // Two lengths (UTF-16 chars, then bytes), each 1 or 2 bytes.
                pos += if (xml[pos].toInt() and 0x80 != 0) 2 else 1
                var len = xml[pos].toInt() and 0xFF
                if (len and 0x80 != 0) {
                    len = ((len and 0x7F) shl 8) or (xml[pos + 1].toInt() and 0xFF); pos += 2
                } else pos += 1
                out.add(String(xml, pos, len, Charsets.UTF_8))
            } else {
                var len = buf.getShort(pos).toInt() and 0xFFFF
                if (len and 0x8000 != 0) {
                    len = ((len and 0x7FFF) shl 16) or (buf.getShort(pos + 2).toInt() and 0xFFFF); pos += 4
                } else pos += 2
                out.add(String(xml, pos, len * 2, Charsets.UTF_16LE))
            }
        }
        return out
    }
}
