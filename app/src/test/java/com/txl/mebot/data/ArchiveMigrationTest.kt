package com.txl.mebot.data

import android.app.Application
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.local.*
import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ArchiveMigrationTest {
    private lateinit var context: Context
    private var database: MebotDatabase? = null
    private val paths = listOf("media/originals/archive-upgrade-current.png", "media/originals/archive-upgrade-history.png")
    @Before fun setUp() { context = ApplicationProvider.getApplicationContext(); context.deleteDatabase(MebotDatabase.FILE_NAME) }
    @After fun tearDown() { database?.close(); context.deleteDatabase(MebotDatabase.FILE_NAME); paths.forEach { File(context.filesDir, it).delete() } }

    @Test fun migration15To16KeepsAll45DefinitionsRowsCiphertextDraftsAndCurrentAndHistoryFiles() {
        val original = legacy15()
        paths.forEach { File(context.filesDir, it).apply { parentFile!!.mkdirs(); writeBytes("fixture original $it".toByteArray()) } }
        val opened = MebotDatabase.create(context).also { database = it }
        val upgraded = opened.openHelper.writableDatabase
        assertEquals(MebotDatabase.VERSION, upgraded.version)
        assertEquals(original.rows, original.tables.associateWith { upgraded.query("SELECT * FROM `$it` ORDER BY rowid").use(::rows) })
        assertEquals(original.definitions, upgraded.query(definitions).use(::rows).filter { it[2] in original.tables })
        assertEquals(schema(MebotDatabase.VERSION).getString("identityHash"), upgraded.query("SELECT identity_hash FROM room_master_table WHERE id=42").use { it.moveToFirst(); it.getString(0) })
        ArchiveStorageSchema.tables.forEach { table -> upgraded.query("SELECT COUNT(*) FROM `$table`").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) } }
        upgraded.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        paths.forEach { assertEquals("fixture original $it", File(context.filesDir, it).readText()) }
        opened.close()
        val reopened = MebotDatabase.create(context).also { database = it }
        val read = reopened.openHelper.readableDatabase
        assertEquals(original.rows, original.tables.associateWith { read.query("SELECT * FROM `$it` ORDER BY rowid").use(::rows) })
    }

    @Test fun freshAndMigrated16HaveIdenticalArchiveDefinitionsAndImmutableBindingTriggers() {
        legacy15()
        val migrated = MebotDatabase.create(context).also { database = it }
        val actual = migrated.openHelper.readableDatabase.query(definitions).use(::rows).filter { it[2]?.startsWith("archive_") == true }
        migrated.close(); context.deleteDatabase(MebotDatabase.FILE_NAME)
        val fresh = MebotDatabase.create(context).also { database = it }
        val expected = fresh.openHelper.readableDatabase.query(definitions).use(::rows).filter { it[2]?.startsWith("archive_") == true }
        assertEquals(expected, actual)
        assertEquals(2, expected.count { it[0] == "trigger" })
    }

    @Test fun invalidOldSchemaRollsBackEveryAddedTableVersionAndIdentityWithoutLosingOldRows() {
        val original = legacy15()
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(MebotDatabase.FILE_NAME), null).use {
            it.execSQL("DROP INDEX index_subscriptions_coverAssetId")
        }
        database = MebotDatabase.create(context)
        assertTrue(runCatching { database!!.openHelper.writableDatabase }.isFailure)
        database!!.close(); database = null
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(MebotDatabase.FILE_NAME), null).use { old ->
            assertEquals(15, old.version)
            assertEquals(schema(15).getString("identityHash"), old.rawQuery("SELECT identity_hash FROM room_master_table WHERE id=42", null).use { it.moveToFirst(); it.getString(0) })
            assertEquals(original.rows, original.tables.associateWith { old.rawQuery("SELECT * FROM `$it` ORDER BY rowid", null).use(::rows) })
            old.rawQuery("SELECT name FROM sqlite_master WHERE name LIKE 'archive_%'", null).use { assertFalse(it.moveToFirst()) }
        }
    }

    @Test fun exportedSchemaAddsOnly13TablesAndDoesNotRewriteAnyPreviousEntity() {
        val old = schema(15).getJSONArray("entities"); val new = schema(16).getJSONArray("entities")
        assertEquals(45, old.length()); assertEquals(58, new.length())
        val newByName = (0 until new.length()).associate { new.getJSONObject(it).let { row -> row.getString("tableName") to row } }
        for (i in 0 until old.length()) {
            val entity = old.getJSONObject(i)
            assertEquals(entity.toString(), newByName.getValue(entity.getString("tableName")).toString())
        }
        assertEquals(ArchiveStorageSchema.tables.toSet(), newByName.keys.filter { it.startsWith("archive_") }.toSet())
    }

    private data class Legacy(val tables: List<String>, val rows: Map<String, List<List<String?>>>, val definitions: List<List<String?>>)
    private fun legacy15(): Legacy {
        val schema = schema(15); val entities = schema.getJSONArray("entities")
        val names = (0 until entities.length()).map { entities.getJSONObject(it).getString("tableName") }
        val path = context.getDatabasePath(MebotDatabase.FILE_NAME).apply { parentFile!!.mkdirs() }
        return SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            old.setForeignKeyConstraintsEnabled(true)
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i); val name = entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", name))
                val indices = entity.optJSONArray("indices")
                if (indices != null) for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", name))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
            paths.forEachIndexed { index, relative -> old.execSQL("INSERT INTO media_assets(id,source,relativePath,originalName,mimeType,byteCount,width,height,createdAt) VALUES(?, 'IMPORTED', ?, '原图.png','image/png',4,1,1,100)", arrayOf("photo$index", relative)) }
            listOf(
                "INSERT INTO schedule_sessions VALUES ('schedule',100,200)",
                "INSERT INTO schedule_messages VALUES ('schedule-message','schedule',1,'USER','原日程会话',100,NULL)",
                "INSERT INTO schedule_tasks VALUES ('task','schedule','已有日程','完整内容',20000,540,'photo0',1,100,200,NULL)",
                "INSERT INTO schedule_task_versions VALUES ('task',1,20000,'已有日程','完整内容',20000,540,'photo0',100)",
                "INSERT INTO schedule_attachments VALUES ('schedule','photo0','schedule-message',100,0,'原图.png')",
                "INSERT INTO schedule_conversation_drafts VALUES ('schedule','日程未发草稿',2,200)",
                "INSERT INTO model_settings VALUES (1,'fixture-model','https://example.com/v1','fixture-image','https://example.com/images',768,X'010203',X'040506',2,100)",
                "INSERT INTO flashnotes VALUES ('flash','已有闪念','闪念全文','PENDING',100,300,NULL,2)",
                "INSERT INTO flashnote_drafts VALUES ('flash-draft','','闪念未发草稿',NULL,NULL,3,400,'')",
                "INSERT INTO flashnote_attachments VALUES ('flash','photo0','原图.png',1)",
                "INSERT INTO dayima_profile VALUES (1,6000,100,1)",
                "INSERT INTO dayima_periods VALUES ('period',20000,100,100,1)",
                "INSERT INTO dayima_notes VALUES ('note',20000,'健康原备注','period',100,100,1)",
                "INSERT INTO dayima_chat_sessions VALUES ('health','原有独立聊天','ACTIVE',100,200,1)",
                "INSERT INTO dayima_chat_messages VALUES ('health-message','health',1,'USER','健康原聊天',100)",
                "INSERT INTO dayima_chat_drafts VALUES ('health','健康未发草稿',3,200)",
                "INSERT INTO subscriptions VALUES ('subscription','已有订阅',900,'CNY','MONTH',1,20000,0,20030,NULL,1,'ACTIVE','fixture-account','fixture-payment','完整备注','photo0',100,200,2)",
                "INSERT INTO subscription_conversations VALUES ('sub-chat','subscription',100,200)",
                "INSERT INTO subscription_drafts VALUES ('sub-chat','订阅未发草稿','{}',1,3,200)",
                "INSERT INTO subscription_messages VALUES ('sub-message','sub-chat',1,'USER','订阅原聊天',100)",
                "INSERT INTO subscription_operations VALUES ('sub-op','sub-chat','subscription','sub-message','CREATE','APPLIED','{}',NULL,'subscription',NULL,100,200)",
                "INSERT INTO subscription_changes VALUES ('sub-change','subscription','sub-op','CREATE',NULL,1,NULL,'{}','photo1','photo0',100)",
                "INSERT INTO subscription_operations VALUES ('payment-op','sub-chat','subscription','sub-message','RECORD_PAYMENT','APPLIED','{}',2,'payment',NULL,100,200)",
                "INSERT INTO subscription_payments VALUES ('payment','subscription','event',900,'CNY',20000,'原付款备注','payment-op',200)",
                "INSERT INTO subscription_conversation_media VALUES ('sub-chat','photo0','当前原件',0,0,100)",
                "INSERT INTO subscription_message_media VALUES ('sub-message','sub-chat','photo0','当前原件',0)",
            ).forEach { sql -> old.execSQL(sql) }
            old.version = 15
            Legacy(names, names.associateWith { old.rawQuery("SELECT * FROM `$it` ORDER BY rowid", null).use(::rows) }, old.rawQuery(definitions, null).use(::rows).filter { it[2]?.let(names::contains) == true })
        }
    }
    private val definitions = "SELECT type,name,tbl_name,sql FROM sqlite_master WHERE type IN ('table','index','trigger') AND name NOT LIKE 'sqlite_%' ORDER BY type,name"
    private fun schema(version: Int): JSONObject {
        val path = "schemas/com.txl.mebot.data.local.MebotDatabase/$version.json"
        return JSONObject((File(path).takeIf(File::exists) ?: File("app/$path")).readText()).getJSONObject("database")
    }
    private fun rows(cursor: Cursor): List<List<String?>> = buildList {
        while (cursor.moveToNext()) add((0 until cursor.columnCount).map { index ->
            when (cursor.getType(index)) { Cursor.FIELD_TYPE_NULL -> null; Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(index).joinToString("") { "%02x".format(it) }; else -> cursor.getString(index) }
        })
    }
}
