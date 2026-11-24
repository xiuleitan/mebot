package com.txl.mebot.data

import android.app.Application
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.room.util.TableInfo
import com.txl.mebot.data.local.*
import java.io.File
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** 真实 Room 升级；旧版导出作为独立输入，覆盖全部旧表的不透明数据与关联。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SubscriptionReminderMigrationTest {
    private lateinit var context: Context
    private var database: MebotDatabase? = null
    private val originalPath = "archive/originals/reminder-upgrade-fixture.enc"
    @Before fun setup() { context = ApplicationProvider.getApplicationContext(); context.deleteDatabase(MebotDatabase.FILE_NAME) }
    @After fun teardown() { database?.close(); context.deleteDatabase(MebotDatabase.FILE_NAME); File(context.filesDir, originalPath).delete() }

    @Test fun real16ToCurrentPreservesEvery58TableRowsDefinitionsCiphertextAndFilesThenReopens() {
        val legacy = createLegacy16()
        val bytes = byteArrayOf(0, 1, 2, 127, -1, -2, 8)
        File(context.filesDir, originalPath).apply { parentFile!!.mkdirs(); writeBytes(bytes) }
        val opened = MebotDatabase.create(context).also { database = it }
        val db = opened.openHelper.writableDatabase
        assertEquals(MebotDatabase.VERSION, db.version)
        assertEquals(legacy.rows, legacy.tables.associateWith { db.query(selectOldColumns(it)).use(::rows) })
        assertEquals(legacy.definitions, db.query(definitions).use(::rows).filter { it[2] in legacy.tables }.map(::withoutEmbeddingConfigColumn))
        assertEquals(schema(MebotDatabase.VERSION).getString("identityHash"), db.query("SELECT identity_hash FROM room_master_table WHERE id=42").use { it.moveToFirst(); it.getString(0) })
        db.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        SubscriptionReminderStorageSchema.tables.forEach { table -> db.query("SELECT COUNT(*) FROM `$table`").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) } }
        assertArrayEquals(bytes, File(context.filesDir, originalPath).readBytes())
        opened.close()
        val reopened = MebotDatabase.create(context).also { database = it }.openHelper.readableDatabase
        assertEquals(legacy.rows, legacy.tables.associateWith { reopened.query(selectOldColumns(it)).use(::rows) })
    }

    @Test fun freshCurrentAndMigratedCurrentHaveIdenticalReminderTablesIndicesAndForeignKeys() {
        createLegacy16()
        val migrated = MebotDatabase.create(context).also { database = it }
        val before = SubscriptionReminderStorageSchema.tables.associateWith { TableInfo.read(migrated.openHelper.readableDatabase, it) }
        migrated.close(); context.deleteDatabase(MebotDatabase.FILE_NAME)
        val fresh = MebotDatabase.create(context).also { database = it }
        val after = SubscriptionReminderStorageSchema.tables.associateWith { TableInfo.read(fresh.openHelper.readableDatabase, it) }
        assertEquals(after, before)
        assertEquals(3, after.size)
        assertEquals(2, after.values.sumOf { it.indices!!.size })
        fresh.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
    }

    @Test fun invalid16RollsBackAllThreeTablesVersionAndIdentityWithoutChangingAnyOldRow() {
        val legacy = createLegacy16()
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(MebotDatabase.FILE_NAME), null).use { it.execSQL("DROP INDEX index_archive_records_sourceSessionId") }
        database = MebotDatabase.create(context)
        assertTrue(runCatching { database!!.openHelper.writableDatabase }.isFailure)
        database!!.close(); database = null
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(MebotDatabase.FILE_NAME), null).use { old ->
            assertEquals(16, old.version)
            assertEquals(schema(16).getString("identityHash"), old.rawQuery("SELECT identity_hash FROM room_master_table WHERE id=42", null).use { it.moveToFirst(); it.getString(0) })
            assertEquals(legacy.rows, legacy.tables.associateWith { old.rawQuery("SELECT * FROM `$it` ORDER BY rowid", null).use(::rows) })
            old.rawQuery("SELECT name FROM sqlite_master WHERE name LIKE 'subscription_reminder_%'", null).use { assertFalse(it.moveToFirst()) }
        }
    }

    @Test fun schema17AddsOnlyThreeReminderTablesAndPreservesEveryOldEntityExactly() {
        val old = schema(16).getJSONArray("entities"); val new = schema(17).getJSONArray("entities")
        assertEquals(58, old.length()); assertEquals(61, new.length())
        val after = (0 until new.length()).associate { new.getJSONObject(it).let { item -> item.getString("tableName") to item } }
        for (i in 0 until old.length()) {
            val item = old.getJSONObject(i)
            assertEquals(item.toString(), after.getValue(item.getString("tableName")).toString())
        }
        assertEquals(SubscriptionReminderStorageSchema.tables.toSet(), after.keys.filter { it.startsWith("subscription_reminder_") }.toSet())
    }

    private data class Legacy(val tables: List<String>, val rows: Map<String, List<List<String?>>>, val definitions: List<List<String?>>)
    private fun createLegacy16(): Legacy {
        val schema = schema(16); val entities = schema.getJSONArray("entities")
        val names = (0 until entities.length()).map { entities.getJSONObject(it).getString("tableName") }
        val path = context.getDatabasePath(MebotDatabase.FILE_NAME).apply { parentFile!!.mkdirs() }
        return SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.setForeignKeyConstraintsEnabled(true)
            for (i in 0 until entities.length()) {
                val item = entities.getJSONObject(i); val name = item.getString("tableName")
                db.execSQL(item.getString("createSql").replace("\${TABLE_NAME}", name))
                val indices = item.optJSONArray("indices")
                if (indices != null) for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", name))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.beginTransaction()
            try {
                // 一条合成不透明行覆盖每张旧表；同类型关联列同值，延迟检查支持档案会话/记录的双向绑定。
                db.execSQL("PRAGMA defer_foreign_keys=ON")
                for (i in 0 until entities.length()) {
                    val item = entities.getJSONObject(i); val fields = item.getJSONArray("fields")
                    val columns = (0 until fields.length()).map { fields.getJSONObject(it).getString("columnName") }
                    val values = (0 until fields.length()).map { index ->
                        when (fields.getJSONObject(index).getString("affinity")) {
                            "INTEGER" -> 1L
                            "REAL" -> 1.25
                            "BLOB" -> byteArrayOf(0, -1, 1, -128, 127)
                            else -> "retained-fixture"
                        }
                    }.toTypedArray()
                    db.execSQL("INSERT INTO `${item.getString("tableName")}` (${columns.joinToString { "`$it`" }}) VALUES (${columns.joinToString { "?" }})", values)
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            // schema16 的固定绑定触发器不在 Room JSON 内，按已发布版本原样构造旧库输入。
            db.execSQL("""CREATE TRIGGER IF NOT EXISTS archive_session_target_immutable
                BEFORE UPDATE OF recordId ON archive_sessions
                WHEN OLD.recordId IS NOT NULL AND NEW.recordId IS NOT OLD.recordId
                BEGIN SELECT RAISE(ABORT, 'Archive session target is immutable'); END""")
            db.execSQL("""CREATE TRIGGER IF NOT EXISTS archive_record_source_immutable
                BEFORE UPDATE OF sourceSessionId ON archive_records
                WHEN NEW.sourceSessionId IS NOT OLD.sourceSessionId
                BEGIN SELECT RAISE(ABORT, 'Archive source session is immutable'); END""")
            db.rawQuery("PRAGMA foreign_key_check", null).use { assertFalse(it.moveToFirst()) }
            db.version = 16
            Legacy(names, names.associateWith { db.rawQuery("SELECT * FROM `$it` ORDER BY rowid", null).use(::rows) }, db.rawQuery(definitions, null).use(::rows).filter { it[2]?.let(names::contains) == true })
        }
    }
    private val definitions = "SELECT type,name,tbl_name,sql FROM sqlite_master WHERE type IN ('table','index','trigger') AND name NOT LIKE 'sqlite_%' ORDER BY type,name"
    // 仍逐项检查16的所有列；18唯一追加的nullable档案列由ArchiveEmbeddingMigrationTest单独检查。
    private val oldColumns by lazy {
        val entities = schema(16).getJSONArray("entities")
        (0 until entities.length()).associate { position -> entities.getJSONObject(position).let { entity ->
            val fields = entity.getJSONArray("fields")
            entity.getString("tableName") to (0 until fields.length()).map { fields.getJSONObject(it).getString("columnName") }
        } }
    }
    private fun selectOldColumns(table: String) = "SELECT ${oldColumns.getValue(table).joinToString { "`$it`" }} FROM `$table` ORDER BY rowid"
    private fun withoutEmbeddingConfigColumn(row: List<String?>) = row.mapIndexed { index, value ->
        if (index == 3 && row[2] == "archive_index_settings") value?.replace(Regex(",\\s*`configurationPayload` BLOB"), "") else value
    }
    private fun schema(version: Int): JSONObject {
        val path = "schemas/com.txl.mebot.data.local.MebotDatabase/$version.json"
        return JSONObject((File(path).takeIf(File::exists) ?: File("app/$path")).readText()).getJSONObject("database")
    }
    private fun rows(cursor: Cursor): List<List<String?>> = buildList {
        while (cursor.moveToNext()) add((0 until cursor.columnCount).map { index -> when (cursor.getType(index)) {
            Cursor.FIELD_TYPE_NULL -> null
            Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(index).joinToString("") { "%02x".format(it) }
            else -> cursor.getString(index)
        } })
    }
}
