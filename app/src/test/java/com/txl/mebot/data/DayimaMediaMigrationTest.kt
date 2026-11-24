package com.txl.mebot.data

import android.app.Application
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.util.TableInfo
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.local.MebotDatabase
import java.io.File
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
class DayimaMediaMigrationTest {
    private lateinit var context: Context
    private var database: MebotDatabase? = null
    private val original = "media/originals/dayima-migration-original.bin"
    private val added = setOf("dayima_draft_media", "dayima_message_media", "dayima_period_media")
    @Before fun setup() { context = ApplicationProvider.getApplicationContext(); context.deleteDatabase(MebotDatabase.FILE_NAME) }
    @After fun cleanup() { database?.close(); context.deleteDatabase(MebotDatabase.FILE_NAME); File(context.filesDir, original).delete() }

    @Test fun production17And18UpgradeToCurrentPreservesEveryOldCellDefinitionTriggerAndOriginalThenReopens() {
        for (version in listOf(17, 18)) {
            val before = legacy(version)
            val file = File(context.filesDir, original).apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(0, 1, -1, -128, 127)) }
            val bytes = file.readBytes()
            val opened = MebotDatabase.create(context).also { database = it }
            val db = opened.openHelper.writableDatabase
            assertEquals(MebotDatabase.VERSION, db.version)
            assertEquals(before.rows, before.columns.mapValues { (table, columns) -> db.query(select(table, columns)).use(::rows) })
            val oldDefinitions = db.query(definitions).use(::rows).filter { it[2] in before.columns }.map { row ->
                row.mapIndexed { i, value -> if (version == 17 && i == 3 && row[2] == "archive_index_settings") value?.replace(Regex(",\\s*`configurationPayload` BLOB"), "") else value }
            }
            assertEquals(before.definitions, oldDefinitions)
            db.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
            assertEquals(schema(MebotDatabase.VERSION).getString("identityHash"), db.query("SELECT identity_hash FROM room_master_table WHERE id=42").use { it.moveToFirst(); it.getString(0) })
            assertArrayEquals(bytes, file.readBytes())
            added.forEach { table -> db.query("SELECT COUNT(*) FROM `$table`").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) } }
            opened.close()
            val reopened = MebotDatabase.create(context).also { database = it }
            assertEquals(before.rows, before.columns.mapValues { (table, columns) -> reopened.openHelper.readableDatabase.query(select(table, columns)).use(::rows) })
            reopened.close(); database = null; context.deleteDatabase(MebotDatabase.FILE_NAME)
        }
    }

    @Test fun freshAndMigrated19HaveTheSameThreeTablesSevenIndicesAndForeignKeys() {
        legacy(18)
        val migrated = MebotDatabase.create(context).also { database = it }
        val before = added.associateWith { TableInfo.read(migrated.openHelper.readableDatabase, it) }
        migrated.close(); context.deleteDatabase(MebotDatabase.FILE_NAME)
        val fresh = MebotDatabase.create(context).also { database = it }
        val after = added.associateWith { TableInfo.read(fresh.openHelper.readableDatabase, it) }
        assertEquals(before, after); assertEquals(7, after.values.sumOf { it.indices!!.size })
        assertEquals(setOf("CASCADE", "NO ACTION", "SET NULL"), after.values.flatMap { it.foreignKeys.map { key -> key.onDelete } }.toSet())
        val old = schema(18).getJSONArray("entities"); val current = schema(19).getJSONArray("entities")
        assertEquals(61, old.length()); assertEquals(64, current.length())
        val entities = (0 until current.length()).associate { current.getJSONObject(it).let { item -> item.getString("tableName") to item } }
        for (index in 0 until old.length()) { val item = old.getJSONObject(index); assertEquals(item.toString(), entities.getValue(item.getString("tableName")).toString()) }
        assertEquals(added, entities.keys.filter { it.startsWith("dayima_") && it.endsWith("_media") }.toSet())
    }

    @Test fun failedValidationRollsBackNewTablesVersionIdentityOldRowsAnd17sAddedColumn() {
        for (version in listOf(17, 18)) {
            val before = legacy(version)
            SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(MebotDatabase.FILE_NAME), null).use { it.execSQL("DROP INDEX index_archive_records_sourceSessionId") }
            val opened = MebotDatabase.create(context).also { database = it }
            assertTrue(runCatching { opened.openHelper.writableDatabase }.isFailure)
            opened.close(); database = null
            SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(MebotDatabase.FILE_NAME), null).use { db ->
                assertEquals(version, db.version)
                assertEquals(schema(version).getString("identityHash"), db.rawQuery("SELECT identity_hash FROM room_master_table WHERE id=42", null).use { it.moveToFirst(); it.getString(0) })
                assertEquals(before.rows, before.columns.mapValues { (table, columns) -> db.rawQuery(select(table, columns), null).use(::rows) })
                db.rawQuery("SELECT name FROM sqlite_master WHERE name LIKE 'dayima_%_media'", null).use { assertFalse(it.moveToFirst()) }
                db.rawQuery("PRAGMA table_info(archive_index_settings)", null).use { cursor ->
                    val columns = buildList { while (cursor.moveToNext()) add(cursor.getString(1)) }
                    assertEquals(version == 18, "configurationPayload" in columns)
                }
            }
            context.deleteDatabase(MebotDatabase.FILE_NAME)
        }
    }

    private data class Legacy(val columns: Map<String, List<String>>, val rows: Map<String, List<List<String?>>>, val definitions: List<List<String?>>)
    private fun legacy(version: Int): Legacy {
        val source = schema(version); val entities = source.getJSONArray("entities")
        val columns = (0 until entities.length()).associate { entities.getJSONObject(it).let { entity ->
            val fields = entity.getJSONArray("fields")
            entity.getString("tableName") to (0 until fields.length()).map { fields.getJSONObject(it).getString("columnName") }
        } }
        val path = context.getDatabasePath(MebotDatabase.FILE_NAME).apply { parentFile!!.mkdirs() }
        return SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.setForeignKeyConstraintsEnabled(true)
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index); val name = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", name))
                val indices = entity.optJSONArray("indices")
                if (indices != null) for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", name))
            }
            val setup = source.getJSONArray("setupQueries"); for (index in 0 until setup.length()) db.execSQL(setup.getString(index))
            db.beginTransaction()
            try {
                db.execSQL("PRAGMA defer_foreign_keys=ON")
                for (index in 0 until entities.length()) {
                    val entity = entities.getJSONObject(index); val fields = entity.getJSONArray("fields"); val names = columns.getValue(entity.getString("tableName"))
                    val values = (0 until fields.length()).map { field -> when (fields.getJSONObject(field).getString("affinity")) {
                        "INTEGER" -> 1L; "REAL" -> 1.25; "BLOB" -> byteArrayOf(0, -1, 1, -128, 127); else -> "retained-fixture"
                    } }.toTypedArray()
                    db.execSQL("INSERT INTO `${entity.getString("tableName")}` (${names.joinToString { "`$it`" }}) VALUES (${names.joinToString { "?" }})", values)
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            db.execSQL("""CREATE TRIGGER IF NOT EXISTS archive_session_target_immutable BEFORE UPDATE OF recordId ON archive_sessions
                WHEN OLD.recordId IS NOT NULL AND NEW.recordId IS NOT OLD.recordId BEGIN SELECT RAISE(ABORT, 'Archive session target is immutable'); END""")
            db.execSQL("""CREATE TRIGGER IF NOT EXISTS archive_record_source_immutable BEFORE UPDATE OF sourceSessionId ON archive_records
                WHEN NEW.sourceSessionId IS NOT OLD.sourceSessionId BEGIN SELECT RAISE(ABORT, 'Archive source session is immutable'); END""")
            db.version = version
            Legacy(columns, columns.mapValues { (name, fields) -> db.rawQuery(select(name, fields), null).use(::rows) }, db.rawQuery(definitions, null).use(::rows))
        }
    }
    private val definitions = "SELECT type,name,tbl_name,sql FROM sqlite_master WHERE type IN ('table','index','trigger') AND name NOT LIKE 'sqlite_%' AND name NOT IN ('room_master_table','android_metadata') ORDER BY type,name"
    private fun select(name: String, columns: List<String>) = "SELECT ${columns.joinToString { "`$it`" }} FROM `$name` ORDER BY rowid"
    private fun schema(version: Int): JSONObject = JSONObject((File("schemas/com.txl.mebot.data.local.MebotDatabase/$version.json").takeIf(File::exists)
        ?: File("app/schemas/com.txl.mebot.data.local.MebotDatabase/$version.json")).readText()).getJSONObject("database")
    private fun rows(cursor: Cursor): List<List<String?>> = buildList {
        while (cursor.moveToNext()) add((0 until cursor.columnCount).map { index -> when (cursor.getType(index)) {
            Cursor.FIELD_TYPE_NULL -> null; Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(index).joinToString("") { "%02x".format(it) }; else -> cursor.getString(index)
        } })
    }
}
