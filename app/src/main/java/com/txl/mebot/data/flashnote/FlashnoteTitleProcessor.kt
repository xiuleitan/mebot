package com.txl.mebot.data.flashnote

import com.txl.mebot.data.local.FlashnoteAttachment
import com.txl.mebot.data.local.FlashnoteEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class FlashnoteTitleSnapshot(val note: FlashnoteEntity, val attachments: List<FlashnoteAttachment>) {
    override fun toString() = "FlashnoteTitleSnapshot([local record redacted])"
}

class FlashnoteTitleProcessor(
    private val repository: FlashnoteRepository,
    private val generator: FlashnoteTitleGenerator,
    private val prepareImages: suspend (List<FlashnoteAttachment>) -> List<FlashnoteTitleImage>,
) {
    suspend fun generate(id: String, expectedRevision: Long) {
        val snapshot = try { repository.titleSnapshot(id, expectedRevision) }
        catch (_: FlashnoteNotFound) { throw FlashnoteTitleException(FlashnoteTitleFailure.CHANGED) }
        catch (_: FlashnoteConflict) { throw FlashnoteTitleException(FlashnoteTitleFailure.CHANGED) }
        validateFlashnoteTitleInput(snapshot.note.content, snapshot.attachments.count { it.isImage })
        val images = prepareImages(snapshot.attachments)
        if (images.size != snapshot.attachments.count { it.isImage }) throw FlashnoteTitleException(FlashnoteTitleFailure.IMAGE)
        val title = validatedFlashnoteTitle(generator.generate(snapshot.note.content, images))
        currentCoroutineContext().ensureActive()
        try { repository.applyGeneratedTitle(snapshot, title) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: FlashnoteNotFound) { throw FlashnoteTitleException(FlashnoteTitleFailure.CHANGED) }
        catch (_: FlashnoteConflict) { throw FlashnoteTitleException(FlashnoteTitleFailure.CHANGED) }
        catch (_: Exception) { throw FlashnoteTitleException(FlashnoteTitleFailure.LOCAL_SAVE) }
    }
}
