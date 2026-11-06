package dev.piko.server.torrent

/**
 * 种子解析自检（不依赖测试框架）：`gradlew :server:compileTestKotlin` 后用 main() 跑。
 * 覆盖多文件/单文件、tracker 去重、v2 拒绝、文件数上限、嵌套上限与结构错误。
 */
private fun bstr(value: String): String = "${value.toByteArray(Charsets.UTF_8).size}:$value"

private fun bint(value: Long): String = "i$value" + "e"

private fun torrentWith(
    name: String,
    files: List<Pair<String, Long>>,
    announce: String? = "udp://tracker.example:1337/announce",
    announceList: List<List<String>> = emptyList(),
): ByteArray {
    val info = buildString {
        append("d")
        append(bstr("name")).append(bstr(name))
        append(bstr("name.utf-8")).append(bstr(name))
        append(bstr("piece length")).append(bint(262144))
        append(bstr("pieces")).append(bstr("0123456789abcdefghij"))
        append(bstr("files")).append("l")
        for ((path, size) in files) {
            append("d")
            append(bstr("length")).append(bint(size))
            append(bstr("path")).append("l").append(path.split('/').joinToString("") { bstr(it) }).append("e")
            append("e")
        }
        append("e")
        append("e")
    }
    val root = buildString {
        append("d")
        append(bstr("announce")).append(bstr(announce.orEmpty()))
        if (announceList.isNotEmpty()) {
            append(bstr("announce-list")).append("l")
            for (tier in announceList) {
                append("l")
                for (tracker in tier) append(bstr(tracker))
                append("e")
            }
            append("e")
        }
        append(bstr("info")).append(info)
        append("e")
    }
    return root.toByteArray(Charsets.UTF_8)
}

private fun singleFileTorrent(name: String, length: Long): ByteArray {
    val info = "d" + bstr("name") + bstr(name) + bstr("length") + bint(length) + bstr("piece length") + bint(16384) +
        bstr("pieces") + bstr("0123456789abcdefghij") + "e"
    return ("d" + bstr("announce") + bstr("https://t.example/announce") + bstr("info") + info + "e").toByteArray(Charsets.UTF_8)
}

private fun v2Torrent(): ByteArray {
    val info = "d" + bstr("name") + bstr("v2-only") + bstr("piece length") + bint(16384) +
        bstr("file tree") + "d" + bstr("a.txt") + "d" + bstr("") + "d" + bstr("length") + bint(10) + "e" + "e" + "e" +
        bstr("meta version") + bint(2) + "e"
    return ("d" + bstr("info") + info + "e").toByteArray(Charsets.UTF_8)
}

private fun manyFiles(count: Int): ByteArray =
    torrentWith("many", List(count) { index -> "dir/file$index.bin" to 1L })

private fun expect(condition: Boolean, message: String, failures: MutableList<String>) {
    if (!condition) failures += message
}

fun main() {
    val failures = mutableListOf<String>()

    val multi = TorrentParser.parse(
        torrentWith(
            "电影合集",
            listOf("电影/上部/01.mp4" to 1000L, "电影/上部/02.mp4" to 2000L, "电影/说明.txt" to 100L),
            announceList = listOf(listOf("udp://tracker.one:1/announce"), listOf("udp://tracker.two:2/announce"), listOf("udp://tracker.one:1/announce")),
        ),
    )
    expect(multi.name == "电影合集", "name 解析错误：${multi.name}", failures)
    expect(multi.totalSizeBytes == 3100L, "总大小错误：${multi.totalSizeBytes}", failures)
    expect(multi.files.map { it.path } == listOf("电影/上部/01.mp4", "电影/上部/02.mp4", "电影/说明.txt"), "文件路径错误", failures)
    expect(multi.folderCount == 2, "目录数错误：${multi.folderCount}", failures)
    expect(multi.trackers.size == 3, "tracker 未去重：${multi.trackers}", failures)
    expect(multi.infoHash.length == 40 && multi.infoHash.all { it in "0123456789abcdef" }, "infohash 格式错误：${multi.infoHash}", failures)
    expect(multi.magnet.startsWith("magnet:?xt=urn:btih:${multi.infoHash}&dn="), "magnet 前缀错误", failures)
    expect(multi.magnet.contains("&tr=udp%3A%2F%2Ftracker.one%3A1%2Fannounce"), "magnet 缺少 URL 编码的 tracker", failures)

    // infohash 只覆盖 info 字典：换 announce 不影响，换文件内容则变化
    val sameInfoOtherTracker = TorrentParser.parse(
        torrentWith(
            "电影合集",
            listOf("电影/上部/01.mp4" to 1000L, "电影/上部/02.mp4" to 2000L, "电影/说明.txt" to 100L),
            announce = "udp://other.example:9/announce",
        ),
    )
    expect(sameInfoOtherTracker.infoHash == multi.infoHash, "infohash 不应受 announce 影响", failures)
    val changed = TorrentParser.parse(torrentWith("电影合集", listOf("电影/上部/01.mp4" to 1001L)))
    expect(changed.infoHash != multi.infoHash, "文件长度变化后 infohash 应变化", failures)

    val single = TorrentParser.parse(singleFileTorrent("单文件.mkv", 4096))
    expect(single.files.single().path == "单文件.mkv", "单文件路径错误", failures)
    expect(single.totalSizeBytes == 4096L, "单文件大小错误", failures)
    expect(single.folderCount == 0, "单文件目录数应为 0", failures)

    val v2 = runCatching { TorrentParser.parse(v2Torrent()) }.exceptionOrNull()
    expect(v2 is TorrentParseException && v2.code == "torrent_v2_unsupported", "v2 种子未被拒绝：$v2", failures)

    val tooMany = runCatching { TorrentParser.parse(manyFiles(10001)) }.exceptionOrNull()
    expect(tooMany is TorrentParseException && tooMany.code == "torrent_too_many_files", "文件数上限未生效：$tooMany", failures)

    val malformed = runCatching { TorrentParser.parse("not-a-torrent".toByteArray()) }.exceptionOrNull()
    expect(malformed is TorrentParseException && malformed.code == "torrent_invalid", "非法 bencode 未被拒绝：$malformed", failures)

    val deep = runCatching {
        val nested = "l".repeat(40) + "i1e" + "e".repeat(40)
        TorrentParser.parse(
            ("d" + bstr("info") + "d" + bstr("name") + bstr("deep") + bstr("files") + nested + "ee").toByteArray(),
        )
    }.exceptionOrNull()
    expect(deep is TorrentParseException && deep.code == "torrent_invalid", "嵌套深度上限未生效：$deep", failures)

    val overflow = runCatching {
        TorrentParser.parse(torrentWith("overflow", listOf("a" to Long.MAX_VALUE, "b" to 1L)))
    }.exceptionOrNull()
    expect(overflow is TorrentParseException && overflow.code == "torrent_invalid", "总大小溢出未拦截：$overflow", failures)

    if (failures.isEmpty()) {
        println("TorrentParserCheck: PASS (8 cases)")
    } else {
        failures.forEach { println("TorrentParserCheck: FAIL - $it") }
        kotlin.system.exitProcess(1)
    }
}
