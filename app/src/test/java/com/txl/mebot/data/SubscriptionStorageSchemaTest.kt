package com.txl.mebot.data

import android.app.Application
import android.content.Context
import android.database.Cursor
import androidx.room.util.TableInfo
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.local.SubscriptionStorageSchema
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** 检查未编号 DDL 与 Room 实体一致，并验证在旧结构上追加表不会改写原有数据。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SubscriptionStorageSchemaTest {
    @Suppress("RestrictedApi", "DEPRECATION")
    @Test fun unnumberedSchemaMatchesRoomEntitiesAndPreservesExistingRowsDefinitionsVersionAndIdentity() {
        withLegacy { db, names ->
            val oldRows = names.associateWith { db.query("SELECT * FROM `$it` ORDER BY rowid").use(::rows) }
            val oldDefinitions = definitions(db, names)
            val oldHash = db.query("SELECT identity_hash FROM room_master_table WHERE id=42").use(::rows)
            db.beginTransaction()
            try { SubscriptionStorageSchema.createTables(db); db.setTransactionSuccessful() } finally { db.endTransaction() }
            val context = ApplicationProvider.getApplicationContext<Context>()
            context.deleteDatabase(SubscriptionStorageTestDatabase.TEST_FILE)
            val expected = SubscriptionStorageTestDatabase.create(context)
            try {
                val fresh = expected.openHelper.readableDatabase
                SubscriptionStorageSchema.tables.forEach { table -> assertEquals(table, TableInfo.read(fresh, table), TableInfo.read(db, table)) }
                assertEquals(14, db.version) // 这是追加结构测试，不分配或写入应用迁移版本。
                assertEquals(oldHash, db.query("SELECT identity_hash FROM room_master_table WHERE id=42").use(::rows))
                assertEquals(oldRows, names.associateWith { db.query("SELECT * FROM `$it` ORDER BY rowid").use(::rows) })
                assertEquals(oldDefinitions, definitions(db, names))
                db.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
            } finally { expected.close(); context.deleteDatabase(SubscriptionStorageTestDatabase.TEST_FILE) }
        }
    }

    @Test fun failedTransactionRollsBackNewTablesAndLeavesExistingRecordsAndVersionUntouched() {
        withLegacy { db, names ->
            val before = names.associateWith { db.query("SELECT * FROM `$it` ORDER BY rowid").use(::rows) }
            db.beginTransaction()
            try { SubscriptionStorageSchema.createTables(db) } finally { db.endTransaction() }
            db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='subscriptions'").use { assertFalse(it.moveToFirst()) }
            assertEquals(before, names.associateWith { db.query("SELECT * FROM `$it` ORDER BY rowid").use(::rows) })
            assertEquals(14, db.version)
        }
    }

    private fun withLegacy(block: (SupportSQLiteDatabase, List<String>) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val filename = "subscription-unallocated-schema-fixture.db"
        val schemaPath = "schemas/com.txl.mebot.data.local.MebotDatabase/14.json"
        val schema = JSONObject((File(schemaPath).takeIf { it.isFile } ?: File("app/$schemaPath")).readText()).getJSONObject("database")
        val entities = schema.getJSONArray("entities")
        val names = (0 until entities.length()).map { entities.getJSONObject(it).getString("tableName") }
        context.deleteDatabase(filename)
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context).name(filename)
            .callback(object : SupportSQLiteOpenHelper.Callback(14) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    for (index in 0 until entities.length()) {
                        val entity = entities.getJSONObject(index)
                        val table = entity.getString("tableName")
                        db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                        entity.optJSONArray("indices")?.let { indices ->
                            for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                        }
                    }
                    val setup = schema.getJSONArray("setupQueries")
                    for (index in 0 until setup.length()) db.execSQL(setup.getString(index))
                    db.execSQL("INSERT INTO schedule_sessions VALUES ('existing-schedule',1,2)")
                    db.execSQL("INSERT INTO schedule_conversation_drafts VALUES ('existing-schedule','原日程草稿',3,4)")
                    db.execSQL("INSERT INTO flashnotes VALUES ('existing-note','原标题','原闪念全文','PENDING',1,2,NULL,1)")
                    db.execSQL("INSERT INTO dayima_profile VALUES (1,6000,2,1)")
                }
                override fun onConfigure(db: SupportSQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("本测试不执行版本迁移")
            }).build())
        try { block(helper.writableDatabase, names) } finally { helper.close(); context.deleteDatabase(filename) }
    }

    private fun definitions(db: SupportSQLiteDatabase, names: List<String>) = db.query(
        "SELECT type,name,tbl_name,sql FROM sqlite_master WHERE type IN ('table','index') AND name NOT LIKE 'sqlite_%' ORDER BY type,name",
    ).use(::rows).filter { it[2] in names }
    private fun rows(cursor: Cursor): List<List<String?>> = buildList {
        while (cursor.moveToNext()) add((0 until cursor.columnCount).map { if (cursor.isNull(it)) null else cursor.getString(it) })
    }
}
