package com.txl.mebot.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** 主库读写与版本条件更新；跨记录事务和参数校验由 Repository 协调。 */
@Dao
interface FlashnoteDao {
    // 冲突必须向上报告，不用 REPLACE 覆盖原记录或连带删除编辑草稿。
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertNote(note: FlashnoteEntity)

    @Query("SELECT * FROM flashnotes WHERE id = :id")
    suspend fun note(id: String): FlashnoteEntity?

    @Query("SELECT * FROM flashnotes WHERE id = :id")
    fun observeNote(id: String): Flow<FlashnoteEntity?>

    @Query("SELECT * FROM flashnotes WHERE status = :status ORDER BY createdAt ASC, id ASC")
    suspend fun notes(status: FlashnoteStatus): List<FlashnoteEntity>

    @Query("SELECT * FROM flashnotes WHERE status = :status ORDER BY createdAt ASC, id ASC")
    fun observeNotes(status: FlashnoteStatus): Flow<List<FlashnoteEntity>>

    @Query("SELECT status, COUNT(*) AS count FROM flashnotes GROUP BY status")
    fun observeCounts(): Flow<List<FlashnoteStatusCount>>

    @Query("DELETE FROM flashnotes WHERE id = :id")
    suspend fun deleteNote(id: String): Int

    @Query("""UPDATE flashnotes SET title = :title, content = :content,
        updatedAt = :updatedAt, revision = revision + 1 WHERE id = :id AND revision = :expectedRevision""")
    suspend fun updateContent(id: String, title: String, content: String, updatedAt: Long, expectedRevision: Long): Int

    @Query("""UPDATE flashnotes SET title = :title, updatedAt = :updatedAt, revision = revision + 1
        WHERE id = :id AND revision = :expectedRevision""")
    suspend fun updateTitle(id: String, title: String, updatedAt: Long, expectedRevision: Long): Int

    @Query("""UPDATE flashnotes SET status = :status, completedAt = :completedAt,
        updatedAt = :updatedAt, revision = revision + 1 WHERE id = :id AND revision = :expectedRevision""")
    suspend fun updateStatus(id: String, status: FlashnoteStatus, completedAt: Long?, updatedAt: Long, expectedRevision: Long): Int

    @Query("DELETE FROM flashnotes WHERE id = :id AND revision = :expectedRevision")
    suspend fun deleteNoteIfRevision(id: String, expectedRevision: Long): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertDraft(draft: FlashnoteDraftEntity)

    @Query("SELECT * FROM flashnote_drafts WHERE `key` = :key")
    suspend fun draft(key: String): FlashnoteDraftEntity?

    @Query("SELECT * FROM flashnote_drafts WHERE `key` = :key")
    fun observeDraft(key: String): Flow<FlashnoteDraftEntity?>

    @Query("SELECT * FROM flashnote_drafts WHERE editingNoteId = :noteId")
    suspend fun editingDraft(noteId: String): FlashnoteDraftEntity?

    @Query("SELECT * FROM flashnote_drafts WHERE editingNoteId IS NULL ORDER BY updatedAt DESC, `key` ASC")
    suspend fun creationDrafts(): List<FlashnoteDraftEntity>

    @Query("DELETE FROM flashnote_drafts WHERE `key` = :key")
    suspend fun deleteDraft(key: String): Int

    @Query("""UPDATE flashnote_drafts SET title = :title, content = :content,
        updatedAt = :updatedAt, revision = revision + 1 WHERE `key` = :key AND revision = :expectedRevision""")
    suspend fun updateDraft(key: String, title: String, content: String, updatedAt: Long, expectedRevision: Long): Int

    @Query("""UPDATE flashnote_drafts SET attachmentSignature = :signature, revision = revision + 1,
        updatedAt = :updatedAt WHERE `key` = :key AND revision = :expectedRevision""")
    suspend fun updateAttachmentSignature(key: String, signature: String, updatedAt: Long, expectedRevision: Long): Int

    @Query("DELETE FROM flashnote_drafts WHERE `key` = :key AND revision = :expectedRevision")
    suspend fun deleteDraftIfRevision(key: String, expectedRevision: Long): Int
}
