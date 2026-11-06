package dev.piko.server.torrent

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * 自研 bencode 解析（不引第三方库）。只做种子「打开」需要的部分：
 * 名字、文件清单、总大小、announce(=tracker)、info 原始字节的 SHA-1（v1 infohash）。
 *
 * 首期不支持 BT v2（BEP 52 的 `file tree`）：纯 v2 种子直接拒绝，明示原因。
 */
class TorrentParseException(val code: String, message: String) : Exception(message)

data class TorrentFileEntry(val path: String, val sizeBytes: Long)

data class TorrentListing(
    val name: String,
    val totalSizeBytes: Long,
    val files: List<TorrentFileEntry>,
    val trackers: List<String>,
    val infoHash: String,
    val magnet: String,
    val folderCount: Int,
)

object TorrentParser {
    const val MAX_BYTES = 10 * 1024 * 1024
    const val MAX_FILES = 10_000
    const val MAX_DEPTH = 32

    fun parse(bytes: ByteArray): TorrentListing {
        if (bytes.size > MAX_BYTES) throw TorrentParseException("torrent_too_large", "种子文件超过 10MB")
        val cursor = Cursor(bytes)
        val root = readValue(cursor, 0)
        if (root !is BDict) throw invalid("种子根节点不是字典")
        val info = root.entries["info"] as? BDict ?: throw invalid("种子缺少 info 字典")

        // 纯 v2（只有 file tree、没有 v1 的 files/length）：首期不支持
        val fileList = info.entries["files"]
        if (fileList == null && info.entries["length"] == null && info.entries["file tree"] != null) {
            throw TorrentParseException("torrent_v2_unsupported", "暂不支持 BT v2 种子")
        }

        val name = (info.entries["name.utf-8"] as? BStr)?.text()
            ?.takeIf { it.isNotBlank() }
            ?: (info.entries["name"] as? BStr)?.text()?.takeIf { it.isNotBlank() }
            ?: "未命名种子"

        val files = when {
            fileList is BList -> readFiles(fileList)
            info.entries["length"] != null -> readSingleFile(info, name)
            else -> throw invalid("种子既没有文件列表，也没有单文件长度")
        }
        if (files.size > MAX_FILES) {
            throw TorrentParseException("torrent_too_many_files", "种子文件数超过 ${MAX_FILES}，暂不支持")
        }

        var total = 0L
        for (file in files) {
            total = try {
                Math.addExact(total, file.sizeBytes)
            } catch (_: ArithmeticException) {
                throw invalid("种子总大小超出可表示范围")
            }
        }

        val trackers = readTrackers(root)
        val infoHash = sha1Hex(bytes, info.rawStart, info.rawEnd)
        val magnet = buildMagnet(name, infoHash, trackers)
        val folders = files.asSequence()
            .map { it.path.substringBeforeLast('/', "") }
            .filter { it.isNotEmpty() }
            .distinct()
            .count()
        return TorrentListing(name, total, files, trackers, infoHash, magnet, folders)
    }

    private fun readFiles(list: BList): List<TorrentFileEntry> {
        if (list.items.isEmpty()) throw invalid("种子文件列表为空")
        val files = ArrayList<TorrentFileEntry>(list.items.size)
        for (item in list.items) {
            if (item !is BDict) throw invalid("文件条目不是字典")
            val path = (item.entries["path.utf-8"] as? BList) ?: (item.entries["path"] as? BList)
                ?: throw invalid("文件条目缺少 path")
            val segments = path.items.map { part ->
                (part as? BStr)?.text()?.takeIf { it.isNotBlank() } ?: throw invalid("文件路径片段无效")
            }
            if (segments.isEmpty()) throw invalid("文件路径为空")
            val size = readLength(item) ?: throw invalid("文件条目缺少 length")
            files += TorrentFileEntry(segments.joinToString("/"), size)
        }
        return files
    }

    private fun readSingleFile(info: BDict, name: String): List<TorrentFileEntry> {
        val size = readLength(info) ?: throw invalid("单文件种子缺少 length")
        return listOf(TorrentFileEntry(name, size))
    }

    private fun readLength(dict: BDict): Long? {
        val value = dict.entries["length"] as? BInt ?: return null
        if (value.value < 0) throw invalid("文件长度为负数")
        return value.value
    }

    private fun readTrackers(root: BDict): List<String> {
        val trackers = LinkedHashSet<String>()
        (root.entries["announce"] as? BStr)?.text()?.trim()?.takeIf { it.isNotEmpty() }?.let { trackers += it }
        val announceList = root.entries["announce-list"] as? BList
        announceList?.items?.forEach { tier ->
            if (tier is BList) {
                tier.items.forEach { entry ->
                    (entry as? BStr)?.text()?.trim()?.takeIf { it.isNotEmpty() }?.let { trackers += it }
                }
            } else {
                (tier as? BStr)?.text()?.trim()?.takeIf { it.isNotEmpty() }?.let { trackers += it }
            }
        }
        return trackers.toList()
    }

    private fun buildMagnet(name: String, infoHash: String, trackers: List<String>): String {
        val encodedName = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20")
        val encodedTrackers = trackers.joinToString("") { tracker ->
            "&tr=" + URLEncoder.encode(tracker, StandardCharsets.UTF_8).replace("+", "%20")
        }
        return "magnet:?xt=urn:btih:$infoHash&dn=$encodedName$encodedTrackers"
    }

    private fun sha1Hex(bytes: ByteArray, start: Int, end: Int): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(bytes.copyOfRange(start, end))
        return digest.joinToString("") { String.format("%02x", it) }
    }

    private fun invalid(message: String) = TorrentParseException("torrent_invalid", message)

    // ---------- bencode 基础解析 ----------

    private class Cursor(val data: ByteArray) {
        var pos: Int = 0
    }

    private sealed interface BValue
    private class BInt(val value: Long) : BValue
    private class BStr(val raw: ByteArray) : BValue {
        fun text(): String = String(raw, StandardCharsets.UTF_8)
    }
    private class BList(val items: List<BValue>) : BValue
    private class BDict(val entries: Map<String, BValue>, val rawStart: Int, val rawEnd: Int) : BValue

    private fun readValue(cursor: Cursor, depth: Int): BValue {
        if (depth > MAX_DEPTH) throw invalid("bencode 嵌套超过 $MAX_DEPTH 层")
        if (cursor.pos >= cursor.data.size) throw invalid("bencode 提前结束")
        return when (val byte = cursor.data[cursor.pos]) {
            'i'.code.toByte() -> readInt(cursor)
            'l'.code.toByte() -> readList(cursor, depth)
            'd'.code.toByte() -> readDict(cursor, depth)
            in '0'.code.toByte()..'9'.code.toByte() -> readString(cursor)
            else -> throw invalid("非法 bencode 字符：0x${String.format("%02x", byte)}")
        }
    }

    private fun readInt(cursor: Cursor): BInt {
        cursor.pos++ // 跳过 i
        val start = cursor.pos
        while (cursor.pos < cursor.data.size && cursor.data[cursor.pos] != 'e'.code.toByte()) {
            cursor.pos++
        }
        if (cursor.pos >= cursor.data.size) throw invalid("bencode 整数缺少结尾")
        val text = String(cursor.data, start, cursor.pos - start, StandardCharsets.US_ASCII)
        cursor.pos++ // 跳过 e
        if (text.isEmpty() || text == "-" || text.startsWith("+")) throw invalid("非法 bencode 整数：$text")
        return BInt(text.toLongOrNull() ?: throw invalid("bencode 整数溢出：$text"))
    }

    private fun readString(cursor: Cursor): BStr {
        val lengthStart = cursor.pos
        while (cursor.pos < cursor.data.size && cursor.data[cursor.pos] in '0'.code.toByte()..'9'.code.toByte()) {
            cursor.pos++
        }
        if (cursor.pos >= cursor.data.size || cursor.data[cursor.pos] != ':'.code.toByte()) throw invalid("bencode 字符串缺少冒号")
        val lengthText = String(cursor.data, lengthStart, cursor.pos - lengthStart, StandardCharsets.US_ASCII)
        if (lengthText.isEmpty()) throw invalid("bencode 字符串长度为空")
        val length = lengthText.toIntOrNull() ?: throw invalid("bencode 字符串长度非法：$lengthText")
        cursor.pos++ // 跳过冒号
        val end = cursor.pos + length
        if (length < 0 || end > cursor.data.size) throw invalid("bencode 字符串长度超出数据范围")
        val raw = cursor.data.copyOfRange(cursor.pos, end)
        cursor.pos = end
        return BStr(raw)
    }

    private fun readList(cursor: Cursor, depth: Int): BList {
        cursor.pos++ // 跳过 l
        val items = mutableListOf<BValue>()
        while (true) {
            if (cursor.pos >= cursor.data.size) throw invalid("bencode 列表缺少结尾")
            if (cursor.data[cursor.pos] == 'e'.code.toByte()) {
                cursor.pos++
                return BList(items)
            }
            items += readValue(cursor, depth + 1)
            if (items.size > MAX_FILES * 4) throw TorrentParseException("torrent_too_many_files", "种子结构过大")
        }
    }

    private fun readDict(cursor: Cursor, depth: Int): BDict {
        val start = cursor.pos
        cursor.pos++ // 跳过 d
        val entries = LinkedHashMap<String, BValue>()
        while (true) {
            if (cursor.pos >= cursor.data.size) throw invalid("bencode 字典缺少结尾")
            if (cursor.data[cursor.pos] == 'e'.code.toByte()) {
                cursor.pos++
                return BDict(entries, start, cursor.pos)
            }
            val key = readValue(cursor, depth + 1) as? BStr ?: throw invalid("bencode 字典的 key 不是字符串")
            entries[key.text()] = readValue(cursor, depth + 1)
        }
    }
}
