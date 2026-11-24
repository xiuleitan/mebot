package com.txl.mebot.data

import android.app.Application
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.archive.*
import com.txl.mebot.data.local.*
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ArchiveEmbeddingMigrationTest {
    private lateinit var context: Context
    private var database: MebotDatabase? = null
    private val original = "archive/originals/embedding-migration-real.bin"
    private val cipher = ArchiveCipher { SecretKeySpec(ByteArray(32) { 6 }, "AES") }
    @Before fun setup() { context = ApplicationProvider.getApplicationContext(); context.deleteDatabase(MebotDatabase.FILE_NAME) }
    @After fun teardown() { database?.close(); context.deleteDatabase(MebotDatabase.FILE_NAME); File(context.filesDir, original).delete() }

    @Test fun production16And17To18PreserveAllOldTableCellsDefinitionsAndFilesThenReopen() {
        for (version in listOf(16, 17)) {
            val before = legacy(version)
            val file = File(context.filesDir, original).apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, -1, -128, 0)) }
            val bytes = file.readBytes()
            val opened = MebotDatabase.create(context).also { database = it }
            val db = opened.openHelper.writableDatabase
            assertEquals(MebotDatabase.VERSION, db.version)
            assertEquals(before.rows, before.columns.mapValues { (name, columns) -> db.query(select(name, columns)).use(::rows) })
            assertEquals(before.definitions, db.query(definitions).use(::rows).filter { it[2] in before.columns }.map(::withoutAddedColumn))
            db.query("SELECT configurationPayload FROM archive_index_settings").use { assertTrue(it.moveToFirst()); assertTrue(it.isNull(0)) }
            db.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
            assertArrayEquals(bytes, file.readBytes())
            assertEquals(schema(MebotDatabase.VERSION).getString("identityHash"), db.query("SELECT identity_hash FROM room_master_table WHERE id=42").use { it.moveToFirst(); it.getString(0) })
            opened.close()
            val reopened = MebotDatabase.create(context).also { database = it }
            assertEquals(before.rows, before.columns.mapValues { (name, columns) -> reopened.openHelper.readableDatabase.query(select(name, columns)).use(::rows) })
            reopened.close(); database = null; context.deleteDatabase(MebotDatabase.FILE_NAME)
        }
    }

    @Test fun realEncryptedArchiveDraftMessageAndAuthenticatedAttachmentRemainReadableAfter17To18() = runBlocking {
        legacy(17)
        val bytes = "synthetic original attachment".toByteArray()
        val file = File(context.filesDir, original).apply { parentFile!!.mkdirs() }
        file.outputStream().use { cipher.encryptStream(ByteArrayInputStream(bytes), it, "asset-file:real-asset", 1024) { _, _ -> } }
        fun encrypted(json: JSONObject, purpose: String) = cipher.encrypt(json.toString().toByteArray(), purpose)
        val content = ArchiveContent("原始证件资料", fields = listOf(ArchiveField("证件号", "001234X")), assetIds = listOf("real-asset"))
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(MebotDatabase.FILE_NAME), null).use { db ->
            db.execSQL("INSERT INTO archive_sessions VALUES ('real-session',NULL,'ACTIVE',10,10)")
            db.execSQL("INSERT INTO archive_records VALUES ('real-record','real-session',?,1,10,10,NULL)", arrayOf(encrypted(ArchiveCodec.content(content), "record:real-record")))
            db.execSQL("UPDATE archive_sessions SET recordId='real-record' WHERE id='real-session'")
            db.execSQL("INSERT INTO archive_drafts VALUES ('real-session',?,4,1,NULL,NULL,1,10)", arrayOf(encrypted(ArchiveCodec.draft(ArchiveDraftContent("尚未发送的补充", content.copy(body = "未确认修改"))), "draft:real-session")))
            db.execSQL("INSERT INTO archive_messages VALUES ('real-message','real-session',1,'USER',?,10)", arrayOf(encrypted(JSONObject().put("text", "原始聊天资料"), "message:real-message")))
            val info = ArchiveAssetInfo("原件.txt", "text/plain", bytes.size.toLong(), MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            db.execSQL("INSERT INTO archive_assets VALUES ('real-asset',?,?,?,10)", arrayOf(original, encrypted(ArchiveCodec.asset(info), "asset:real-asset"), file.length()))
            db.execSQL("INSERT INTO archive_session_assets VALUES ('real-session','real-asset',0,0)")
            db.execSQL("INSERT INTO archive_message_assets VALUES ('real-message','real-session','real-asset',0)")
            db.execSQL("INSERT INTO archive_record_assets VALUES ('real-record','real-asset',0)")
        }
        val opened = MebotDatabase.create(context).also { database = it }
        val storage = ArchiveStorage(opened, opened.archives(), cipher)
        val records = ArchiveRepository(storage); val files = ArchiveAttachmentRepository(context, storage)
        assertEquals("001234X", records.record("real-record")!!.content.fields.single().value)
        val restored = records.context("real-session")
        assertEquals("尚未发送的补充", restored.draft.content.input); assertEquals("未确认修改", restored.draft.content.candidate!!.body)
        assertEquals("原始聊天资料", restored.messages.single().text)
        files.withDecryptedFile("real-asset") { assertArrayEquals(bytes, it.readBytes()) }
        assertTrue(file.exists()); assertFalse(file.readBytes().contentEquals(bytes))
    }

    @Test fun invalid17RollsBackAddedColumnVersionIdentityAndEveryOldCell() {
        val before = legacy(17)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(MebotDatabase.FILE_NAME), null).use { it.execSQL("DROP INDEX index_archive_records_sourceSessionId") }
        database = MebotDatabase.create(context)
        assertTrue(runCatching { database!!.openHelper.writableDatabase }.isFailure)
        database!!.close(); database = null
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(MebotDatabase.FILE_NAME), null).use { db ->
            assertEquals(17, db.version)
            assertEquals(schema(17).getString("identityHash"), db.rawQuery("SELECT identity_hash FROM room_master_table WHERE id=42", null).use { it.moveToFirst(); it.getString(0) })
            assertEquals(before.rows, before.columns.mapValues { (name, columns) -> db.rawQuery(select(name, columns), null).use(::rows) })
            db.rawQuery("PRAGMA table_info(archive_index_settings)", null).use { cursor ->
                val names = buildList { while (cursor.moveToNext()) add(cursor.getString(1)) }
                assertFalse(names.contains("configurationPayload"))
            }
        }
    }

    @Test fun freshAndMigrated18HaveSameDefinitionsAndOnlyOneNullableFieldWasAdded() {
        legacy(17)
        val upgraded = MebotDatabase.create(context).also { database = it }
        val actual = upgraded.openHelper.readableDatabase.query(definitions).use(::rows)
        upgraded.close(); context.deleteDatabase(MebotDatabase.FILE_NAME)
        val fresh = MebotDatabase.create(context).also { database = it }
        assertEquals(actual, fresh.openHelper.readableDatabase.query(definitions).use(::rows))
        val before = schema(17).getJSONArray("entities"); val after = schema(18).getJSONArray("entities")
        assertEquals(61, before.length()); assertEquals(61, after.length())
        val entities = (0 until after.length()).associate { after.getJSONObject(it).let { row -> row.getString("tableName") to row } }
        for (i in 0 until before.length()) {
            val old = before.getJSONObject(i); val new = entities.getValue(old.getString("tableName"))
            if (old.getString("tableName") != "archive_index_settings") assertEquals(old.toString(), new.toString())
            else {
                val fields = new.getJSONArray("fields"); val oldFields = old.getJSONArray("fields")
                assertEquals(oldFields.length() + 1, fields.length())
                for (j in 0 until oldFields.length()) assertEquals(oldFields.getJSONObject(j).toString(), fields.getJSONObject(j).toString())
                val added = fields.getJSONObject(fields.length() - 1)
                assertEquals("configurationPayload", added.getString("columnName")); assertEquals("BLOB", added.getString("affinity")); assertFalse(added.optBoolean("notNull", false))
                assertEquals(old.optJSONArray("indices")?.toString(), new.optJSONArray("indices")?.toString())
                assertEquals(old.optJSONArray("foreignKeys")?.toString(), new.optJSONArray("foreignKeys")?.toString())
            }
        }
    }

    private data class Legacy(val columns: Map<String, List<String>>, val rows: Map<String, List<List<String?>>>, val definitions: List<List<String?>>)
    private fun legacy(version: Int): Legacy {
        val source = schema(version); val entities = source.getJSONArray("entities")
        val columns = (0 until entities.length()).associate { position -> entities.getJSONObject(position).let { row ->
            val fields = row.getJSONArray("fields")
            row.getString("tableName") to (0 until fields.length()).map { fields.getJSONObject(it).getString("columnName") }
        } }
        val path = context.getDatabasePath(MebotDatabase.FILE_NAME).apply { parentFile!!.mkdirs() }
        return SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.setForeignKeyConstraintsEnabled(true)
            for (i in 0 until entities.length()) {
                val row = entities.getJSONObject(i); val name = row.getString("tableName")
                db.execSQL(row.getString("createSql").replace("\${TABLE_NAME}", name))
                val indices = row.optJSONArray("indices")
                if (indices != null) for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", name))
            }
            val setup = source.getJSONArray("setupQueries"); for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.beginTransaction()
            try {
                db.execSQL("PRAGMA defer_foreign_keys=ON")
                for (i in 0 until entities.length()) {
                    val row = entities.getJSONObject(i); val fields = row.getJSONArray("fields"); val names = columns.getValue(row.getString("tableName"))
                    val values = (0 until fields.length()).map { position -> when (fields.getJSONObject(position).getString("affinity")) {
                        "INTEGER" -> 1L; "REAL" -> 1.25; "BLOB" -> byteArrayOf(0, 1, -1, -128, 127); else -> "retained-fixture"
                    } }.toTypedArray()
                    db.execSQL("INSERT INTO `${row.getString("tableName")}` (${names.joinToString { "`$it`" }}) VALUES (${names.joinToString { "?" }})", values)
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            db.execSQL("""CREATE TRIGGER IF NOT EXISTS archive_session_target_immutable
            BEFORE UPDATE OF recordId ON archive_sessions
            WHEN OLD.recordId IS NOT NULL AND NEW.recordId IS NOT OLD.recordId
            BEGIN SELECT RAISE(ABORT, 'Archive session target is immutable'); END""")
            db.execSQL("""CREATE TRIGGER IF NOT EXISTS archive_record_source_immutable
            BEFORE UPDATE OF sourceSessionId ON archive_records
            WHEN NEW.sourceSessionId IS NOT OLD.sourceSessionId
            BEGIN SELECT RAISE(ABORT, 'Archive source session is immutable'); END""")
            db.version = version
            Legacy(columns, columns.mapValues { (name, fields) -> db.rawQuery(select(name, fields), null).use(::rows) }, db.rawQuery(definitions, null).use(::rows))
        }
    }
    private val definitions = "SELECT type,name,tbl_name,sql FROM sqlite_master WHERE type IN ('table','index','trigger') AND name NOT LIKE 'sqlite_%' AND name NOT IN ('room_master_table','android_metadata') ORDER BY type,name"
    private fun withoutAddedColumn(row: List<String?>) = row.mapIndexed { i, value -> if (i == 3 && row[2] == "archive_index_settings") value?.replace(Regex(",\\s*`configurationPayload` BLOB"), "") else value }
    private fun select(name: String, columns: List<String>) = "SELECT ${columns.joinToString { "`$it`" }} FROM `$name` ORDER BY rowid"
    private fun schema(version: Int): JSONObject = JSONObject((File("schemas/com.txl.mebot.data.local.MebotDatabase/$version.json").takeIf(File::exists)
        ?: File("app/schemas/com.txl.mebot.data.local.MebotDatabase/$version.json")).readText()).getJSONObject("database")
    private fun rows(cursor: Cursor): List<List<String?>> = buildList {
        while (cursor.moveToNext()) add((0 until cursor.columnCount).map { index -> when (cursor.getType(index)) {
            Cursor.FIELD_TYPE_NULL -> null; Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(index).joinToString("") { "%02x".format(it) }; else -> cursor.getString(index)
        } })
    }
}
