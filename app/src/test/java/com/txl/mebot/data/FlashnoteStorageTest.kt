package com.txl.mebot.data

import android.app.Application
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.local.FlashnoteDraftEntity
import com.txl.mebot.data.local.FlashnoteEntity
import com.txl.mebot.data.local.FlashnoteStatus
import com.txl.mebot.data.local.MebotDatabase
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** 使用开发机上的真实 SQLite 验证新表及历史数据升级，不操作手机。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class FlashnoteStorageTest {
    private lateinit var context: Context
    private lateinit var database: MebotDatabase

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(MebotDatabase.FILE_NAME)
        database = MebotDatabase.create(context)
    }

    @After fun tearDown() {
        database.close()
        context.deleteDatabase(MebotDatabase.FILE_NAME)
        File(context.filesDir, PhotoPath).delete()
        File(context.filesDir, AttachmentPath).delete()
    }

    @Test fun freshDatabaseStartsEmptyAndNotesAndDraftsSurviveReopen() = runBlocking {
        val dao = database.flashnoteDao()
        assertTrue(dao.notes(FlashnoteStatus.PENDING).isEmpty())
        assertTrue(dao.notes(FlashnoteStatus.COMPLETED).isEmpty())
        assertTrue(dao.creationDrafts().isEmpty())
        val pending = note("pending", 100).copy(title = "想做的事", content = "完整正文\n第二行 | 链接 https://example.com")
        val completed = note("completed", 200, FlashnoteStatus.COMPLETED)
        val creation = FlashnoteDraftEntity("creation", title = "未发送标题", content = "尚未保存的输入", updatedAt = 300)
        val editing = FlashnoteDraftEntity("editing", title = "编辑标题", content = "编辑还没完成",
            editingNoteId = pending.id, baseRevision = pending.revision, revision = 2, updatedAt = 400)
        dao.insertNote(pending)
        dao.insertNote(completed)
        dao.insertDraft(creation)
        dao.insertDraft(editing)

        database.close()
        database = MebotDatabase.create(context)
        val reopened = database.flashnoteDao()
        assertEquals(pending, reopened.note(pending.id))
        assertEquals(listOf(pending), reopened.observeNotes(FlashnoteStatus.PENDING).first())
        assertEquals(listOf(completed), reopened.notes(FlashnoteStatus.COMPLETED))
        assertEquals(creation, reopened.observeDraft(creation.key).first())
        assertEquals(editing, reopened.editingDraft(pending.id))
        assertEquals(listOf(creation), reopened.creationDrafts())
    }

    @Test fun bothStatusListsUseCreationOrderAndStableIdsInsteadOfUpdateOrCompletionTime() = runBlocking {
        val dao = database.flashnoteDao()
        for (status in FlashnoteStatus.entries) {
            val suffix = status.name
            dao.insertNote(note("new-$suffix", 300, status).copy(updatedAt = 301, completedAt = if (status == FlashnoteStatus.COMPLETED) 400 else null))
            dao.insertNote(note("tie-z-$suffix", 200, status))
            dao.insertNote(note("old-$suffix", 100, status).copy(updatedAt = 9_000, completedAt = if (status == FlashnoteStatus.COMPLETED) 9_000 else null))
            dao.insertNote(note("tie-a-$suffix", 200, status))
            val expected = listOf("old-$suffix", "tie-a-$suffix", "tie-z-$suffix", "new-$suffix")
            assertEquals(expected, dao.notes(status).map { it.id })
            assertEquals(expected, dao.observeNotes(status).first().map { it.id })
        }
    }

    @Test fun duplicateIdsAbortWithoutOverwritingNoteOrDeletingItsEditingDraft() = runBlocking {
        val dao = database.flashnoteDao()
        val original = note("saved", 100)
        val editing = FlashnoteDraftEntity("editing", content = "保留的编辑草稿", editingNoteId = original.id,
            baseRevision = original.revision, updatedAt = 200)
        dao.insertNote(original)
        dao.insertDraft(editing)
        assertTrue(runCatching { dao.insertNote(original.copy(content = "不能覆盖", createdAt = 999)) }.isFailure)
        assertTrue(runCatching { dao.insertDraft(editing.copy(content = "不能覆盖草稿")) }.isFailure)
        assertTrue(runCatching { dao.insertDraft(editing.copy(key = "another-edit")) }.isFailure)
        assertEquals(original, dao.note(original.id))
        assertEquals(editing, dao.draft(editing.key))
    }

    @Test fun editingDraftRequiresItsNoteAndCascadesOnlyWhenThatNoteIsDeleted() = runBlocking {
        val dao = database.flashnoteDao()
        val saved = note("saved", 100)
        val other = note("other", 200)
        val creation = FlashnoteDraftEntity("creation", content = "独立的新建草稿", updatedAt = 300)
        dao.insertNote(saved)
        dao.insertNote(other)
        dao.insertDraft(creation)
        dao.insertDraft(FlashnoteDraftEntity("editing", editingNoteId = saved.id, baseRevision = 1, updatedAt = 400))
        assertTrue(runCatching {
            dao.insertDraft(FlashnoteDraftEntity("orphan", editingNoteId = "missing", updatedAt = 500))
        }.isFailure)
        assertEquals(1, dao.deleteNote(saved.id))
        assertNull(dao.draft("editing"))
        assertNull(dao.draft("orphan"))
        assertEquals(creation, dao.draft(creation.key))
        assertEquals(other, dao.note(other.id))
        assertEquals(0, dao.deleteNote(saved.id))
    }

    @Test fun failedRelatedDraftInsertRollsBackTheWholeTransaction() = runBlocking {
        val dao = database.flashnoteDao()
        val failure = runCatching {
            database.withTransaction {
                dao.insertNote(note("must-rollback", 100))
                dao.insertDraft(FlashnoteDraftEntity("invalid", editingNoteId = "missing", updatedAt = 200))
            }
        }
        assertTrue(failure.isFailure)
        assertNull(dao.note("must-rollback"))
        assertNull(dao.draft("invalid"))
    }

    @Test fun actualVersionEightMigrationPreservesEveryOldTableAndAttachmentFiles() = runBlocking {
        database.close()
        val schemaPath = "schemas/com.txl.mebot.data.local.MebotDatabase/8.json"
        val schemaFile = File(schemaPath).takeIf { it.exists() } ?: File("app/$schemaPath")
        val schema = JSONObject(schemaFile.readText()).getJSONObject("database")
        val entities = schema.getJSONArray("entities")
        val names = (0 until entities.length()).map { entities.getJSONObject(it).getString("tableName") }
        val photo = File(context.filesDir, PhotoPath).apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val attachment = File(context.filesDir, AttachmentPath).apply { parentFile!!.mkdirs(); writeText("原始附件内容，不得被迁移删除") }
        val photoBytes = photo.readBytes()
        val attachmentBytes = attachment.readBytes()
        val path = context.getDatabasePath(MebotDatabase.FILE_NAME).apply { parentFile!!.mkdirs() }
        lateinit var oldRows: Map<String, List<List<String?>>>
        lateinit var oldDefinitions: List<List<String?>>
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            old.setForeignKeyConstraintsEnabled(true)
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                val table = entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                entity.optJSONArray("indices")?.let { indices ->
                    for (i in 0 until indices.length()) old.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            schema.getJSONArray("setupQueries").let { queries ->
                for (index in 0 until queries.length()) old.execSQL(queries.getString(index))
            }
            seedVersionEight(old)
            old.version = 8
            oldRows = snapshotTables(names) { sql -> old.rawQuery(sql, null) }
            oldDefinitions = snapshotDefinitions { sql -> old.rawQuery(sql, null) }
                .filter { row -> row[2]?.let { it in names } == true }
            old.rawQuery("PRAGMA foreign_key_check", null).use { assertFalse(it.moveToFirst()) }
        }

        database = MebotDatabase.create(context)
        val dao = database.flashnoteDao()
        assertTrue(dao.notes(FlashnoteStatus.PENDING).isEmpty()) // 强制实际迁移及 Room schema 校验。
        assertTrue(dao.creationDrafts().isEmpty())
        val upgraded = database.openHelper.readableDatabase
        assertEquals(MebotDatabase.VERSION, upgraded.version)
        assertEquals(oldRows, snapshotTables(names) { sql -> upgraded.query(sql) })
        assertEquals(oldDefinitions, snapshotDefinitions { sql -> upgraded.query(sql) }
            .filter { row -> row[2]?.let { it in names } == true })
        assertArrayEquals(photoBytes, photo.readBytes())
        assertArrayEquals(attachmentBytes, attachment.readBytes())
        upgraded.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        val saved = note("after-upgrade", 500)
        dao.insertNote(saved)
        dao.insertDraft(FlashnoteDraftEntity("after-upgrade-draft", content = "升级后草稿", updatedAt = 600))
        assertEquals(saved, dao.note(saved.id))
        assertEquals("升级后草稿", dao.draft("after-upgrade-draft")?.content)
        assertEquals("原有日程", database.scheduleDao().occurrence("old-occurrence")?.title)
    }

    @Test fun actualVersionNineMigrationPreservesFlashnotesDraftsAndAllExistingScheduleTables() = runBlocking {
        database.close()
        context.deleteDatabase(MebotDatabase.FILE_NAME)
        val schemaPath = "schemas/com.txl.mebot.data.local.MebotDatabase/9.json"
        val schemaFile = File(schemaPath).takeIf { it.exists() } ?: File("app/$schemaPath")
        val schema = JSONObject(schemaFile.readText()).getJSONObject("database")
        val entities = schema.getJSONArray("entities")
        val unchanged = (0 until entities.length()).map { entities.getJSONObject(it).getString("tableName") }.filter { it != "flashnote_drafts" }
        val path = context.getDatabasePath(MebotDatabase.FILE_NAME).apply { parentFile!!.mkdirs() }
        lateinit var oldRows: Map<String, List<List<String?>>>
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            old.setForeignKeyConstraintsEnabled(true)
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                entity.optJSONArray("indices")?.let { indices ->
                    for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            schema.getJSONArray("setupQueries").let { queries -> for (i in 0 until queries.length()) old.execSQL(queries.getString(i)) }
            seedVersionEight(old)
            old.execSQL("INSERT INTO flashnotes VALUES ('flash-old','已有闪念','保留的完整正文','COMPLETED',100,300,200,2)")
            old.execSQL("INSERT INTO flashnote_drafts VALUES ('create-old','','未提交的新建文字',NULL,NULL,3,400)")
            old.execSQL("INSERT INTO flashnote_drafts VALUES ('edit-old','编辑标题','未提交的编辑正文','flash-old',2,4,500)")
            old.version = 9
            oldRows = snapshotTables(unchanged) { sql -> old.rawQuery(sql, null) }
        }
        database = MebotDatabase.create(context)
        val dao = database.flashnoteDao()
        assertEquals("保留的完整正文", dao.note("flash-old")?.content)
        assertEquals(100L, dao.note("flash-old")?.createdAt)
        assertEquals(FlashnoteStatus.COMPLETED, dao.note("flash-old")?.status)
        assertEquals("未提交的新建文字", dao.draft("create-old")?.content)
        assertEquals("未提交的编辑正文", dao.draft("edit-old")?.content)
        assertEquals(4L, dao.draft("edit-old")?.revision)
        assertEquals("", dao.draft("edit-old")?.attachmentSignature)
        assertTrue(database.flashnoteMediaDao().draftAttachments("create-old").isEmpty())
        val upgraded = database.openHelper.readableDatabase
        assertEquals(MebotDatabase.VERSION, upgraded.version)
        assertEquals(oldRows, snapshotTables(unchanged) { sql -> upgraded.query(sql) })
        upgraded.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
    }

    private fun note(id: String, createdAt: Long, status: FlashnoteStatus = FlashnoteStatus.PENDING) = FlashnoteEntity(
        id = id, title = "标题 $id", content = "正文 $id", status = status,
        createdAt = createdAt, updatedAt = createdAt + 1,
        completedAt = if (status == FlashnoteStatus.COMPLETED) 10_000 - createdAt else null,
    )

    private fun seedVersionEight(old: SQLiteDatabase) {
        val day = LocalDate.of(2026, 10, 30).toEpochDay()
        listOf(
            "INSERT INTO media_assets (id,source,relativePath,originalName,mimeType,byteCount,width,height,createdAt) VALUES ('old-photo','IMPORTED','$PhotoPath','旧图.png','image/png',4,1,1,100),('old-file','IMPORTED','$AttachmentPath','原始附件.txt','text/plain',40,0,0,100)",
            "INSERT INTO schedule_sessions VALUES ('old-session',100,200),('old-draft-session',100,250)",
            "INSERT INTO schedule_messages VALUES ('old-message','old-session',1,'USER','保留原始会话',100,NULL),('old-question','old-draft-session',1,'ASSISTANT','几点开始？',150,NULL)",
            "INSERT INTO schedule_tasks VALUES ('old-task','old-session','原有日程','原有详情',$day,540,'old-photo',1,100,200,NULL)",
            "INSERT INTO schedule_task_versions VALUES ('old-task',1,$day,'原有日程','原有详情',$day,540,'old-photo',100)",
            "INSERT INTO schedule_recurrences VALUES ('old-task',1,'DAILY',1,0,NULL,NULL,1)",
            "INSERT INTO schedule_reminder_leads VALUES ('old-task',1,10)",
            "INSERT INTO schedule_occurrences VALUES ('old-occurrence','old-task','date:$day','原有日程','原有详情',$day,540,'old-photo',1,1,NULL,0,100,200,0)",
            "INSERT INTO schedule_reminders VALUES ('old-reminder','old-occurrence',1,10,200000,'Asia/Shanghai','PENDING',NULL,100)",
            "INSERT INTO schedule_attachments VALUES ('old-session','old-photo','old-message',100,0,'旧图.png'),('old-session','old-file','old-message',100,1,'原始附件.txt')",
            "INSERT INTO schedule_operations VALUES ('old-request','old-session','old-task',1,'CREATE','old-fingerprint',NULL,'{}',100)",
            "INSERT INTO schedule_conversation_drafts VALUES ('old-session','旧会话输入',2,200),('old-draft-session','未发送的日程草稿',3,250)",
            "INSERT INTO schedule_pending_questions VALUES ('old-question','old-draft-session','几点开始？',150,NULL,NULL)",
            "INSERT INTO model_settings VALUES (1,'qwen3.8-flash','https://example.com/v1','z-image-turbo','https://example.com/images',768,X'010203',X'040506',2,100)",
            "INSERT INTO schedule_exclusions VALUES ('old-task','excluded-date')",
            "INSERT INTO schedule_occurrence_leads VALUES ('old-occurrence',5)",
            "INSERT INTO media_cleanup VALUES ('media/originals/old-unused.png')",
            "INSERT INTO schedule_local_attachments VALUES ('old-session','old-photo','旧图.png',1,100,1,0,0),('old-session','old-file','原始附件.txt',2,100,0,0,0),('old-draft-session','old-photo','草稿图.png',1,100,1,1,1)",
            "INSERT INTO schedule_cover_jobs VALUES ('old-task','old-session','old-attempt','旧封面提示','FAILED',NULL,NULL,NULL,'NETWORK',200)",
            "INSERT INTO schedule_notification_settings VALUES (1,'ringtones/old-tone.wav','原有铃声.wav',512,1000,200)",
        ).forEach { old.execSQL(it) }
    }

    private fun snapshotTables(names: List<String>, query: (String) -> Cursor): Map<String, List<List<String?>>> =
        names.associateWith { table -> query("SELECT * FROM `$table` ORDER BY rowid").use(::rows) }

    private fun snapshotDefinitions(query: (String) -> Cursor): List<List<String?>> = query(
        "SELECT type,name,tbl_name,sql FROM sqlite_master WHERE type IN ('table','index') AND name NOT LIKE 'sqlite_%' AND name != 'room_master_table' ORDER BY type,name",
    ).use(::rows)

    private fun rows(cursor: Cursor): List<List<String?>> = buildList {
        while (cursor.moveToNext()) add((0 until cursor.columnCount).map { index ->
            when (cursor.getType(index)) {
                Cursor.FIELD_TYPE_NULL -> null
                Cursor.FIELD_TYPE_BLOB -> "blob:" + cursor.getBlob(index).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
                else -> cursor.getString(index)
            }
        })
    }

    companion object {
        private const val PhotoPath = "media/originals/flashnote-migration-test.png"
        private const val AttachmentPath = "media/originals/flashnote-migration-test.txt"
    }
}
