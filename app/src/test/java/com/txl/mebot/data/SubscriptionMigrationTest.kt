package com.txl.mebot.data

import android.app.Application
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.local.MebotDatabase
import com.txl.mebot.data.local.SubscriptionCycleUnit
import com.txl.mebot.data.local.SubscriptionStorageSchema
import com.txl.mebot.data.subscriptions.SubscriptionRepository
import com.txl.mebot.data.subscriptions.SubscriptionStorage
import com.txl.mebot.data.subscriptions.SubscriptionValues
import java.io.File
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SubscriptionMigrationTest {
    private lateinit var context: Context
    private var database: MebotDatabase? = null
    private val fixturePath = "media/originals/subscription-migration-fixture.png"

    @Before fun setUp() { context = ApplicationProvider.getApplicationContext(); context.deleteDatabase(MebotDatabase.FILE_NAME) }
    @After fun tearDown() { database?.close(); context.deleteDatabase(MebotDatabase.FILE_NAME); File(context.filesDir, fixturePath).delete() }

    @Test fun realMigrationFrom14PreservesAllOldDefinitionsRowsCiphertextFilesAndDraftsAndReopensCurrentMain() = runBlocking {
        val original = createLegacy()
        val bytes = "fixture original image bytes".toByteArray()
        File(context.filesDir, fixturePath).apply { parentFile!!.mkdirs(); writeBytes(bytes) }
        val upgraded = MebotDatabase.create(context).also { database = it }
        val db = upgraded.openHelper.writableDatabase // 真实 Room migrate 与完整结构校验，而非直接执行 DDL。
        assertEquals(MebotDatabase.VERSION, db.version)
        assertEquals(original.rows, original.names.associateWith { db.query("SELECT * FROM `$it` ORDER BY rowid").use(::rows) })
        assertEquals(original.definitions, db.query(definitionQuery).use(::rows).filter { it[2] in original.names })
        assertArrayEquals(bytes, File(context.filesDir, fixturePath).readBytes())
        val schema = schema(MebotDatabase.VERSION)
        assertEquals(schema.getString("identityHash"), db.query("SELECT identity_hash FROM room_master_table WHERE id=42").use { it.moveToFirst(); it.getString(0) })
        SubscriptionStorageSchema.tables.forEach { table ->
            db.query("SELECT COUNT(*) FROM `$table`").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        }
        db.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        val records = SubscriptionRepository(SubscriptionStorage(upgraded, upgraded.subscriptions(), upgraded.subscriptionConversations(), upgraded.mediaDao()))
        val saved = records.create("after-upgrade", SubscriptionValues("永久许可", 2000, "CNY", SubscriptionCycleUnit.NONE, 0))
        upgraded.close()
        val reopened = MebotDatabase.create(context).also { database = it }
        assertEquals(saved, reopened.subscriptions().subscription(saved.id))
        assertEquals("日程未发草稿", reopened.conversationDao().draft("schedule")?.inputText)
        assertEquals("健康未发草稿", reopened.dayimaDao().draft("health")?.inputText)
        assertEquals("闪念未发草稿", reopened.flashnoteDao().draft("flash-draft")?.content)
    }

    @Test fun schemaValidationFailureRollsBackAllNineTablesAndKeepsVersion14IdentityAndRecords() {
        val original = createLegacy()
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(MebotDatabase.FILE_NAME), null).use {
            it.execSQL("DROP INDEX index_flashnotes_status_createdAt_id")
        }
        database = MebotDatabase.create(context)
        assertTrue(runCatching { database!!.openHelper.writableDatabase }.isFailure)
        database!!.close(); database = null
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(MebotDatabase.FILE_NAME), null).use { old ->
            assertEquals(14, old.version)
            assertEquals(schema(14).getString("identityHash"), old.rawQuery("SELECT identity_hash FROM room_master_table WHERE id=42", null).use { it.moveToFirst(); it.getString(0) })
            assertEquals(original.rows, original.names.associateWith { old.rawQuery("SELECT * FROM `$it` ORDER BY rowid", null).use(::rows) })
            old.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='subscriptions'", null).use { assertFalse(it.moveToFirst()) }
        }
    }

    @Test fun freshMainDatabaseHasAllNineTablesAndNeverInventsSubscriptionsOrPayments() = runBlocking {
        val fresh = MebotDatabase.create(context).also { database = it }
        assertEquals(MebotDatabase.VERSION, fresh.openHelper.readableDatabase.version)
        assertTrue(fresh.subscriptions().all().isEmpty())
        assertTrue(fresh.subscriptionConversations().conversations(50).isEmpty())
        fresh.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
    }

    private data class Legacy(val names: List<String>, val rows: Map<String, List<List<String?>>>, val definitions: List<List<String?>>)
    private fun createLegacy(): Legacy {
        val exported = schema(14)
        val entities = exported.getJSONArray("entities")
        val names = (0 until entities.length()).map { entities.getJSONObject(it).getString("tableName") }
        val path = context.getDatabasePath(MebotDatabase.FILE_NAME).apply { parentFile!!.mkdirs() }
        return SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            old.setForeignKeyConstraintsEnabled(true)
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                val table = entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                entity.optJSONArray("indices")?.let { indices -> for (j in 0 until indices.length())
                    old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table)) }
            }
            val setup = exported.getJSONArray("setupQueries")
            for (index in 0 until setup.length()) old.execSQL(setup.getString(index))
            listOf(
                "INSERT INTO media_assets (id,source,relativePath,originalName,mimeType,byteCount,width,height,createdAt) VALUES ('photo','IMPORTED','$fixturePath','原图.png','image/png',4,1,1,100)",
                "INSERT INTO schedule_sessions VALUES ('schedule',100,200)",
                "INSERT INTO schedule_messages VALUES ('schedule-message','schedule',1,'USER','原日程会话',100,NULL)",
                "INSERT INTO schedule_tasks VALUES ('task','schedule','已有日程','完整内容',20000,540,'photo',1,100,200,NULL)",
                "INSERT INTO schedule_task_versions VALUES ('task',1,20000,'已有日程','完整内容',20000,540,'photo',100)",
                "INSERT INTO schedule_attachments VALUES ('schedule','photo','schedule-message',100,0,'原图.png')",
                "INSERT INTO schedule_conversation_drafts VALUES ('schedule','日程未发草稿',2,200)",
                "INSERT INTO model_settings VALUES (1,'fixture-model','https://example.com/v1','fixture-image','https://example.com/images',768,X'010203',X'040506',2,100)",
                "INSERT INTO flashnotes VALUES ('flash','已有闪念','闪念全文','PENDING',100,300,NULL,2)",
                "INSERT INTO flashnote_drafts VALUES ('flash-draft','','闪念未发草稿',NULL,NULL,3,400,'')",
                "INSERT INTO flashnote_attachments VALUES ('flash','photo','原图.png',1)",
                "INSERT INTO dayima_profile VALUES (1,6000,100,1)",
                "INSERT INTO dayima_periods VALUES ('period',20000,100,100,1)",
                "INSERT INTO dayima_notes VALUES ('note',20000,'健康原备注','period',100,100,1)",
                "INSERT INTO dayima_chat_sessions VALUES ('health','原有独立聊天','ACTIVE',100,200,1)",
                "INSERT INTO dayima_chat_messages VALUES ('health-message','health',1,'USER','健康原聊天',100)",
                "INSERT INTO dayima_chat_drafts VALUES ('health','健康未发草稿',3,200)",
            ).forEach { sql -> old.execSQL(sql) }
            old.version = 14
            Legacy(names, names.associateWith { old.rawQuery("SELECT * FROM `$it` ORDER BY rowid", null).use(::rows) },
                old.rawQuery(definitionQuery, null).use(::rows).filter { row -> row[2]?.let { names.contains(it) } == true })
        }
    }

    private fun schema(version: Int): JSONObject {
        val path = "schemas/com.txl.mebot.data.local.MebotDatabase/$version.json"
        return JSONObject((File(path).takeIf { it.exists() } ?: File("app/$path")).readText()).getJSONObject("database")
    }
    private val definitionQuery = "SELECT type,name,tbl_name,sql FROM sqlite_master WHERE type IN ('table','index') AND name NOT LIKE 'sqlite_%' ORDER BY type,name"
    private fun rows(cursor: Cursor): List<List<String?>> = buildList {
        while (cursor.moveToNext()) add((0 until cursor.columnCount).map {
            when (cursor.getType(it)) { Cursor.FIELD_TYPE_NULL -> null; Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(it).joinToString("") { b -> "%02x".format(b) }; else -> cursor.getString(it) }
        })
    }
}
