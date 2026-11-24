package com.txl.mebot.ui.archive

import com.txl.mebot.data.archive.ArchiveAssetInfo
import com.txl.mebot.data.archive.ArchiveAttachmentRepository
import kotlinx.coroutines.CancellationException

internal data class ArchiveImageAlbum(val selected: ArchiveAssetInfo, val imageIds: List<String>)

/** 只在调用方当前档案/消息提供的附件集合中浏览，保留原顺序并排除普通文件。 */
internal suspend fun archiveImageAlbum(selectedId: String, candidates: List<String>, attachments: ArchiveAttachmentRepository): ArchiveImageAlbum {
    require(selectedId in candidates)
    val selected = attachments.metadata(selectedId)
    if (!selected.mimeType.startsWith("image/")) return ArchiveImageAlbum(selected, emptyList())
    val images = candidates.distinct().filter { id ->
        if (id == selectedId) true else try { attachments.metadata(id).mimeType.startsWith("image/") }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
    }
    return ArchiveImageAlbum(selected, images)
}
