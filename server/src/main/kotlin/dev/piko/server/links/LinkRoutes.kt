package dev.piko.server.links

import dev.piko.server.ErrorDetail
import dev.piko.server.ErrorResponse
import dev.piko.server.LinkFileItem
import dev.piko.server.LinkParseRequest
import dev.piko.server.LinkParseResponse
import dev.piko.server.LinkSaveRequest
import dev.piko.server.LinkSaveResponse
import dev.piko.server.ServiceGraph
import dev.piko.server.drive.requireLogin
import dev.piko.server.respondError
import dev.piko.shared.data.InstantFileItem
import dev.piko.shared.data.MagnetResolutionResult
import dev.piko.shared.state.InstantSheetState
import io.github.nihildigit.pikpak.CreateUrlResult
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.MagnetResource
import io.github.nihildigit.pikpak.ResolvedFile
import io.github.nihildigit.pikpak.shareIdFromUrl
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * 链接解析与离线提交。
 *
 * 三类链接各走一条既有实现，不复制解析逻辑：
 * - 磁力 / ed2k：`InstantMagnetRepository.resolve`（云端未收录时返回空）
 * - PikPak 分享：`PikoDriveRepository.shareInfo`，唯一根目录时再展开一层
 * - 直链：`InstantMagnetRepository.probeHttpResource`（只看响应头，不下载正文）
 *
 * 分享识别复用 `InstantSheetState` companion 的正则，避免两套规则。
 */
fun Route.linkRoutes(graph: ServiceGraph) {
    route("/api/links") {
        post("/parse") {
            if (graph.requireLogin(call)) return@post
            val body = runCatching { call.receive<LinkParseRequest>() }.getOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse(ErrorDetail("bad_request", "请求体必须是 JSON")))
            val link = body.link.trim()
            if (link.isEmpty()) {
                return@post call.respondError(HttpStatusCode.BadRequest, "missing_link", "link 不能为空")
            }
            val shared = link.startsWith("magnet:", ignoreCase = true) || link.startsWith("ed2k://", ignoreCase = true)
            val shareLink = InstantSheetState.findShareLink(link)
            val resolved: Result<MagnetResolutionResult?> = when {
                shared -> graph.instantRepository.resolve(link)
                shareLink != null -> resolveShare(graph, shareLink, body.passCode.trim())
                else -> graph.instantRepository.probeHttpResource(link)
            }
            resolved.fold(
                onSuccess = { result ->
                    if (result == null) {
                        return@fold call.respondError(
                            HttpStatusCode.NotFound,
                            "link_not_resolved",
                            "没能解析出文件列表：磁力未被云端收录，或直链不是可下载的资源",
                        )
                    }
                    val kind = when {
                        shared -> "magnet"
                        shareLink != null -> "share"
                        else -> "http"
                    }
                    call.respond(
                        LinkParseResponse(
                            kind = kind,
                            name = result.resource.name,
                            truncated = result.resource.truncated,
                            // 分享的提取码令牌不从这里下发：保存时按原链接与提取码重新解析
                            passCodeToken = null,
                            files = result.items.map {
                                LinkFileItem(
                                    name = it.file.name,
                                    sizeBytes = it.file.size,
                                    gcid = it.file.gcid,
                                    instantReady = it.isInstantReady,
                                )
                            },
                        ),
                    )
                },
                onFailure = {
                    call.respondError(HttpStatusCode.BadGateway, "parse_failed", it.message ?: "解析失败")
                },
            )
        }

        /** 提交到云端离线任务；秒传直接完成时返回产出文件 ID。 */
        post("/save") {
            if (graph.requireLogin(call)) return@post
            val body = runCatching { call.receive<LinkSaveRequest>() }.getOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse(ErrorDetail("bad_request", "请求体必须是 JSON")))
            val link = body.link.trim()
            if (link.isEmpty()) {
                return@post call.respondError(HttpStatusCode.BadRequest, "missing_link", "link 不能为空")
            }
            graph.instantRepository.enqueueOfflineTask(link, targetParentId = body.parentId.trim())
                .fold(
                    onSuccess = { result ->
                        when (result) {
                            is CreateUrlResult.Queued -> call.respond(LinkSaveResponse(taskId = result.task.id))
                            is CreateUrlResult.InstantComplete ->
                                call.respond(LinkSaveResponse(instantComplete = true, fileId = result.file?.id))
                        }
                    },
                    onFailure = {
                        call.respondError(HttpStatusCode.BadGateway, "offline_submit_failed", it.message ?: "提交离线任务失败")
                    },
                )
        }
    }
}

/** 分享：读顶层，唯一根目录是文件夹时再展开一层；更深的目录在 M2 标记为不完整。 */
private suspend fun resolveShare(
    graph: ServiceGraph,
    shareLink: String,
    passCode: String,
): Result<MagnetResolutionResult?> = runCatching {
    val shareId = requireNotNull(shareIdFromUrl(shareLink)) { "无法从链接里取到分享 ID" }
    val info = graph.driveRepository.shareInfo(shareId, passCode).getOrThrow()
    val root = info.files.singleOrNull()?.takeIf { it.isFolder }
    var truncated = info.nextPageToken.isNotEmpty()
    val files = mutableListOf<Pair<String, FileStat>>()
    if (root != null) {
        val (children, morePages) = graph.driveRepository
            .shareFolderFull(shareId, info.passCodeToken, root.id)
            .getOrThrow()
        truncated = truncated || morePages || children.any { it.isFolder }
        children.filterNot { it.isFolder }.forEach { files += "${root.name}/${it.name}" to it }
    } else {
        truncated = truncated || info.files.any { it.isFolder }
        info.files.filterNot { it.isFolder }.forEach { files += it.name to it }
    }
    val items = files.map { (path, file) ->
        InstantFileItem(
            file = ResolvedFile(path, file.sizeBytes, file.hash.ifBlank { null }),
            isInstantReady = file.hash.isNotBlank(),
        )
    }
    MagnetResolutionResult(
        resource = MagnetResource(root?.name ?: info.title, items.map { it.file }, truncated),
        items = items,
        instantReadyCount = items.count { it.isInstantReady },
        totalCount = items.size,
    )
}
