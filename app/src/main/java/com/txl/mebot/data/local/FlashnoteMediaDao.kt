package com.txl.mebot.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

private const val DraftMedia = """SELECT a.*, l.displayName, l.position FROM flashnote_draft_attachments l
    JOIN media_assets a ON a.id = l.assetId WHERE l.draftKey = :key ORDER BY l.position, l.assetId"""
private const val NoteMedia = """SELECT a.*, l.displayName, l.position FROM flashnote_attachments l
    JOIN media_assets a ON a.id = l.assetId WHERE l.noteId = :id ORDER BY l.position, l.assetId"""

@Dao
interface FlashnoteMediaDao {
    @Query(DraftMedia) suspend fun draftAttachments(key: String): List<FlashnoteAttachment>
    @Query(NoteMedia) suspend fun noteAttachments(id: String): List<FlashnoteAttachment>
    @Query(NoteMedia) fun observeNoteAttachments(id: String): Flow<List<FlashnoteAttachment>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertDraft(item: FlashnoteDraftAttachmentEntity): Long
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertNote(item: FlashnoteAttachmentEntity)
    @Query("DELETE FROM flashnote_draft_attachments WHERE draftKey = :key AND assetId = :assetId")
    suspend fun removeDraft(key: String, assetId: String): Int
    @Query("DELETE FROM flashnote_attachments WHERE noteId = :id") suspend fun clearNote(id: String)
    @Query("""SELECT f.*,
        (SELECT a.relativePath FROM flashnote_attachments l JOIN media_assets a ON a.id = l.assetId
            WHERE l.noteId = f.id AND a.mimeType GLOB 'image/*' AND a.width > 0 AND a.height > 0
            ORDER BY l.position, l.assetId LIMIT 1) AS coverRelativePath,
        (SELECT COUNT(*) FROM flashnote_attachments l WHERE l.noteId = f.id) AS attachmentCount
        FROM flashnotes f WHERE f.status = :status ORDER BY f.createdAt, f.id""")
    fun observeCards(status: FlashnoteStatus): Flow<List<FlashnoteCardRecord>>
}
