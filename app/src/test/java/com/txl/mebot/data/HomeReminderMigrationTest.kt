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
class HomeReminderMigrationTest {
    private val tables = listOf("home_events", "home_flashnote_days", "dayima_reminder_plans")
    private lateinit var context: Context
    private var opened: MebotDatabase? = null
    @Before fun setup() { context = ApplicationProvider.getApplicationContext(); context.deleteDatabase(MebotDatabase.FILE_NAME) }
    @After fun teardown() { opened?.close(); context.deleteDatabase(MebotDatabase.FILE_NAME) }

    @Test fun actual19To20PreservesAllExistingRowsDefinitionsAndFilesThenReopens() {
        val legacy = legacy()
        val file = File(context.filesDir, "media/home-migration-fixture.bin").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(0, -1, 99, 1)) }
        try {
            val db = MebotDatabase.create(context).also { opened = it }.openHelper.writableDatabase
            assertEquals(MebotDatabase.VERSION, db.version)
            assertEquals(legacy.first, legacy.first.keys.associateWith { db.query("SELECT * FROM `$it` ORDER BY rowid").use(::rows) })
            assertEquals(legacy.second, db.query(definitions).use(::rows).filter { it[2] in legacy.first.keys })
            assertArrayEquals(byteArrayOf(0, -1, 99, 1), file.readBytes())
            tables.forEach { db.query("SELECT COUNT(*) FROM `$it`").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) } }
            db.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
            assertEquals(schema(MebotDatabase.VERSION).getString("identityHash"), db.query("SELECT identity_hash FROM room_master_table WHERE id=42").use { it.moveToFirst(); it.getString(0) })
            opened!!.close()
            val reopened = MebotDatabase.create(context).also { opened = it }.openHelper.readableDatabase
            assertEquals(legacy.first, legacy.first.keys.associateWith { reopened.query("SELECT * FROM `$it` ORDER BY rowid").use(::rows) })
        } finally { file.delete() }
    }
    @Test fun freshAndMigratedTablesHaveIdenticalIndicesAndOnlyNewEntitiesAreAdded() {
        val before = schema(19).getJSONArray("entities"); val after = schema(20).getJSONArray("entities")
        val newEntities = (0 until after.length()).associate { after.getJSONObject(it).let { entity -> entity.getString("tableName") to entity } }
        assertEquals(before.length() + 3, after.length())
        for (i in 0 until before.length()) { val item = before.getJSONObject(i); assertEquals(item.toString(), newEntities.getValue(item.getString("tableName")).toString()) }
        legacy()
        val db = MebotDatabase.create(context).also { opened = it }
        val migrated = tables.associateWith { TableInfo.read(db.openHelper.readableDatabase, it) }
        db.close(); context.deleteDatabase(MebotDatabase.FILE_NAME)
        val fresh = MebotDatabase.create(context).also { opened = it }
        assertEquals(migrated, tables.associateWith { TableInfo.read(fresh.openHelper.readableDatabase, it) })
    }
    @Test fun invalidOldSchemaRollsBackEntireUpgradeAndKeepsOldIdentityAndRows() {
        val legacy = legacy()
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(MebotDatabase.FILE_NAME), null).use { it.execSQL("DROP INDEX index_archive_records_sourceSessionId") }
        val db = MebotDatabase.create(context).also { opened = it }
        assertTrue(runCatching { db.openHelper.writableDatabase }.isFailure)
        db.close(); opened = null
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(MebotDatabase.FILE_NAME), null).use { old ->
            assertEquals(19, old.version)
            assertEquals(legacy.first, legacy.first.keys.associateWith { old.rawQuery("SELECT * FROM `$it` ORDER BY rowid", null).use(::rows) })
            tables.forEach { name -> old.rawQuery("SELECT name FROM sqlite_master WHERE name=?", arrayOf(name)).use { assertFalse(it.moveToFirst()) } }
            assertEquals(schema(19).getString("identityHash"), old.rawQuery("SELECT identity_hash FROM room_master_table WHERE id=42", null).use { it.moveToFirst(); it.getString(0) })
        }
    }
    private fun legacy(): Pair<Map<String, List<List<String?>>>, List<List<String?>>> {
        val schema = schema(19); val entities = schema.getJSONArray("entities")
        val names = (0 until entities.length()).map { entities.getJSONObject(it).getString("tableName") }
        val path = context.getDatabasePath(MebotDatabase.FILE_NAME).apply { parentFile!!.mkdirs() }
        return SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.setForeignKeyConstraintsEnabled(true)
            for (i in 0 until entities.length()) {
                val item = entities.getJSONObject(i); val name = item.getString("tableName")
                db.execSQL(item.getString("createSql").replace("\${TABLE_NAME}", name))
                val indices = item.optJSONArray("indices")
                for (j in 0 until (indices?.length() ?: 0)) db.execSQL(requireNotNull(indices).getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", name))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.beginTransaction()
            try {
                db.execSQL("PRAGMA defer_foreign_keys=ON")
                for (i in 0 until entities.length()) {
                    val item = entities.getJSONObject(i); val fields = item.getJSONArray("fields")
                    val columns = (0 until fields.length()).map { fields.getJSONObject(it).getString("columnName") }
                    val values = (0 until fields.length()).map { index -> when (fields.getJSONObject(index).getString("affinity")) {
                        "INTEGER" -> 1L
                        "REAL" -> 1.25
                        "BLOB" -> byteArrayOf(0, -1, 99)
                        else -> "home-retained-fixture"
                    } }.toTypedArray()
                    db.execSQL("INSERT INTO `${item.getString("tableName")}` (${columns.joinToString { "`$it`" }}) VALUES (${columns.joinToString { "?" }})", values)
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            db.version = 19
            names.associateWith { db.rawQuery("SELECT * FROM `$it` ORDER BY rowid", null).use(::rows) } to
                db.rawQuery(definitions, null).use(::rows).filter { it[2] in names }
        }
    }
    private val definitions = "SELECT type,name,tbl_name,sql FROM sqlite_master WHERE type IN ('table','index','trigger') AND name NOT LIKE 'sqlite_%' ORDER BY type,name"
    private fun schema(version: Int): JSONObject {
        val path = "schemas/com.txl.mebot.data.local.MebotDatabase/$version.json"
        return JSONObject((File(path).takeIf(File::exists) ?: File("app/$path")).readText()).getJSONObject("database")
    }
    private fun rows(cursor: Cursor): List<List<String?>> = buildList {
        while (cursor.moveToNext()) add((0 until cursor.columnCount).map { i -> when (cursor.getType(i)) {
            Cursor.FIELD_TYPE_NULL -> null
            Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(i).joinToString("") { "%02x".format(it) }
            else -> cursor.getString(i)
        } })
    }
}
