package com.txl.mebot.data

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.local.MebotDatabase
import com.txl.mebot.data.models.*
import com.txl.mebot.data.schedule.ScheduleConversationRepository
import com.txl.mebot.data.schedule.ScheduleRepository
import java.io.File
import java.io.IOException
import javax.crypto.spec.SecretKeySpec
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ModelSettingsStorageTest {
    private lateinit var context: Context
    private lateinit var database: MebotDatabase
    private lateinit var repository: ModelSettingsRepository
    private val secretKey = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
    private val cipher = AesGcmCredentialCipher { secretKey }

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, MebotDatabase::class.java).build()
        repository = ModelSettingsRepository(database, cipher)
    }

    @After fun tearDown() { database.close(); context.deleteDatabase(MebotDatabase.FILE_NAME) }

    @Test fun credentialEncryptionUsesRandomIvAndRejectsTamperingOrWrongKey() {
        val first = cipher.encrypt(TEST_KEY)
        val second = cipher.encrypt(TEST_KEY)
        assertEquals(TEST_KEY, cipher.decrypt(first))
        assertEquals(12, first.iv.size)
        assertFalse(first.iv.contentEquals(second.iv))
        assertFalse(first.ciphertext.contentEquals(second.ciphertext))
        val changed = first.ciphertext.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() }
        assertTrue(runCatching { cipher.decrypt(first.copy(ciphertext = changed)) }.isFailure)
        val other = AesGcmCredentialCipher { SecretKeySpec(ByteArray(32) { 9 }, "AES") }
        assertTrue(runCatching { other.decrypt(first) }.isFailure)
    }

    @Test fun encryptedKeyAndSettingsSurviveCloseAndReopenWithoutPlaintextInDatabase() = runBlocking {
        database.close()
        database = MebotDatabase.create(context)
        repository = ModelSettingsRepository(database, cipher)
        assertEquals(StoredModelSettings(ModelSettings(), 0, false), repository.load())
        val saved = repository.save(ModelSettings(), ApiKeyChange.Replace(TEST_KEY), 0)
        assertTrue(saved.hasApiKey)
        val record = requireNotNull(database.modelSettingsDao().read())
        assertFalse(requireNotNull(record.keyCiphertext).toString(Charsets.UTF_8).contains(TEST_KEY))
        assertFalse(repository.access(saved.revision).toString().contains(TEST_KEY))
        database.close()
        assertFalse(context.getDatabasePath(MebotDatabase.FILE_NAME).readBytes().toString(Charsets.ISO_8859_1).contains(TEST_KEY))
        database = MebotDatabase.create(context)
        repository = ModelSettingsRepository(database, cipher)
        assertEquals(saved, repository.load())
        assertEquals(TEST_KEY, repository.access(saved.revision).apiKey)
    }

    @Test fun metadataEditKeepsKeyReplacementChangesItAndRemovalOnlyRemovesCredential() = runBlocking {
        val first = repository.save(ModelSettings(), ApiKeyChange.Replace(TEST_KEY), 0)
        val changed = first.settings.copy(languageModel = "qwen3.8-flash-custom")
        val updated = repository.save(changed, ApiKeyChange.Keep, first.revision)
        assertEquals(TEST_KEY, repository.access(updated.revision).apiKey)
        val replaced = repository.save(changed, ApiKeyChange.Replace("sk-another-local-test-only"), updated.revision)
        assertEquals("sk-another-local-test-only", repository.access(replaced.revision).apiKey)
        val removed = repository.removeApiKey(replaced.revision)
        assertFalse(removed.hasApiKey)
        assertEquals(changed, removed.settings)
        assertNull(database.modelSettingsDao().read()?.keyCiphertext)
        assertNull(database.modelSettingsDao().read()?.keyIv)
        assertTrue(runCatching { repository.access(removed.revision) }.exceptionOrNull() is MissingApiKey)
    }

    @Test fun invalidOrStaleSaveNeverOverwritesTheExistingKeyOrSettings() = runBlocking {
        val first = repository.save(ModelSettings(), ApiKeyChange.Replace(TEST_KEY), 0)
        assertTrue(runCatching { repository.save(first.settings.copy(languageEndpoint = "http://example.com/v1"), ApiKeyChange.Replace("bad"), first.revision) }.exceptionOrNull() is ModelConfigurationError)
        assertTrue(runCatching { repository.save(first.settings, ApiKeyChange.Replace("sk-later"), 0) }.exceptionOrNull() is ModelSettingsConflict)
        assertEquals(first, repository.load())
        assertEquals(TEST_KEY, repository.access(first.revision).apiKey)
    }

    @Test fun failedEncryptionLeavesPreviousTransactionAndCredentialIntact() = runBlocking {
        val first = repository.save(ModelSettings(), ApiKeyChange.Replace(TEST_KEY), 0)
        val failing = object : CredentialCipher {
            override fun encrypt(value: String): EncryptedCredential = throw IOException("simulated keystore failure")
            override fun decrypt(value: EncryptedCredential): String = cipher.decrypt(value)
        }
        val broken = ModelSettingsRepository(database, failing)
        assertTrue(runCatching { broken.save(first.settings.copy(imageModel = "another-model"), ApiKeyChange.Replace("sk-local-replacement"), first.revision) }.isFailure)
        assertEquals(first, repository.load())
        assertEquals(TEST_KEY, repository.access(first.revision).apiKey)
    }

    @Test fun unavailableKeystoreIsReportedWithoutErasingEncryptedCredential() = runBlocking {
        val first = repository.save(ModelSettings(), ApiKeyChange.Replace(TEST_KEY), 0)
        val unavailable = object : CredentialCipher {
            override fun encrypt(value: String) = cipher.encrypt(value)
            override fun decrypt(value: EncryptedCredential): String = throw IllegalStateException("missing key")
        }
        val broken = ModelSettingsRepository(database, unavailable)
        assertTrue(runCatching { broken.access(first.revision) }.exceptionOrNull() is CredentialUnavailable)
        assertTrue(broken.load().hasApiKey)
        assertNotNull(database.modelSettingsDao().read()?.keyCiphertext)
    }

    @Test fun validationRejectsCredentialBearingUrlsAndHeaderInjectionButNormalizesBaseUrl() {
        val invalid = listOf("http://example.com/v1", "https://user:password@example.com/v1", "https://example.com/v1?api_key=secret", "https://example.com/v1#secret", "https://{WorkspaceId}.example.com/v1", "https://example.com", "https://example.com:99999/v1")
        invalid.forEach { assertTrue(runCatching { ModelSettings(languageEndpoint = it).validated() }.isFailure) }
        listOf("Bearer sk-value", "sk-one\nsk-two", "sk-one sk-two").forEach { assertTrue(runCatching { validateApiKey(it) }.isFailure) }
        assertEquals("https://example.com/compatible-mode/v1/chat/completions", ModelSettings(languageEndpoint = " https://example.com/compatible-mode/v1/ ").validated().languageEndpoint)
        assertTrue(runCatching { ModelSettings(languageModel = "has a space").validated() }.isFailure)
    }

    @Test fun schemaTwoMigrationKeepsLatestDraftMessagesAndPendingQuestion() = runBlocking {
        database.close()
        val schemaPath = "schemas/com.txl.mebot.data.local.MebotDatabase/2.json"
        val file = File(schemaPath).takeIf { it.exists() } ?: File("app/$schemaPath")
        val schema = JSONObject(file.readText()).getJSONObject("database")
        val path = context.getDatabasePath(MebotDatabase.FILE_NAME)
        path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                entity.optJSONArray("indices")?.let { indices ->
                    for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
            old.execSQL("INSERT INTO schedule_sessions VALUES ('draft',100,200)")
            old.execSQL("INSERT INTO schedule_messages VALUES ('user','draft',1,'USER','明天开会',100)")
            old.execSQL("INSERT INTO schedule_messages VALUES ('question','draft',2,'ASSISTANT','几点开会？',150)")
            old.execSQL("INSERT INTO schedule_conversation_drafts VALUES ('draft','下午四点，还没写完',3,200)")
            old.execSQL("INSERT INTO schedule_pending_questions VALUES ('question','draft','几点开会？',150,NULL,NULL)")
            old.version = 2
        }
        database = MebotDatabase.create(context)
        repository = ModelSettingsRepository(database, cipher)
        assertFalse(repository.load().hasApiKey) // 打开新表，强制实际迁移及 schema 验证。
        val conversations = ScheduleConversationRepository(database, ScheduleRepository(database))
        assertEquals("下午四点，还没写完", conversations.openCreation().inputText)
        assertEquals(2, conversations.observeMessages("draft", 40).first().size)
        assertEquals(1, conversations.observePendingQuestions("draft").first().size)
        val saved = repository.save(ModelSettings(), ApiKeyChange.Replace(TEST_KEY), 0)
        assertEquals(TEST_KEY, repository.access(saved.revision).apiKey)
        assertEquals("下午四点，还没写完", conversations.openCreation().inputText)
    }

    @Test fun schemaThreeMigrationKeepsTaskConversationAndEncryptedCredential() = runBlocking {
        database.close()
        val schemaPath = "schemas/com.txl.mebot.data.local.MebotDatabase/3.json"
        val file = File(schemaPath).takeIf { it.exists() } ?: File("app/$schemaPath")
        val schema = JSONObject(file.readText()).getJSONObject("database")
        val path = context.getDatabasePath(MebotDatabase.FILE_NAME)
        path.parentFile!!.mkdirs()
        val encrypted = cipher.encrypt(TEST_KEY)
        val settings = ModelSettings()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                entity.optJSONArray("indices")?.let { indices ->
                    for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
            val day = java.time.LocalDate.of(2026, 10, 22).toEpochDay()
            old.execSQL("INSERT INTO schedule_sessions VALUES ('v3-session',100,200)")
            old.execSQL("INSERT INTO schedule_messages VALUES ('v3-message','v3-session',1,'USER','每天喂猫',100)")
            old.execSQL("INSERT INTO schedule_conversation_drafts VALUES ('v3-session','补充猫粮',3,200)")
            old.execSQL("INSERT INTO schedule_tasks VALUES ('v3-task','v3-session','喂猫','原有详情',$day,1080,NULL,1,100,200,NULL)")
            old.execSQL("INSERT INTO schedule_task_versions VALUES ('v3-task',1,$day,'喂猫','原有详情',$day,1080,NULL,100)")
            old.execSQL("INSERT INTO schedule_recurrences VALUES ('v3-task',1,'DAILY',1,0,NULL,NULL,1)")
            old.execSQL("INSERT INTO schedule_reminder_leads VALUES ('v3-task',1,10)")
            old.execSQL("INSERT INTO schedule_occurrences VALUES ('v3-card','v3-task','date:$day','喂猫','原有详情',$day,1080,NULL,1,1,NULL,0,100,200)")
            old.execSQL("INSERT INTO model_settings VALUES (1,?,?,?,?,768,?,?,2,200)", arrayOf(settings.languageModel, settings.languageEndpoint, settings.imageModel, settings.imageEndpoint, encrypted.ciphertext, encrypted.iv))
            old.version = 3
        }
        database = MebotDatabase.create(context)
        repository = ModelSettingsRepository(database, cipher)
        assertEquals(TEST_KEY, repository.access(2).apiKey)
        val schedules = ScheduleRepository(database)
        val conversations = ScheduleConversationRepository(database, schedules)
        assertEquals("补充猫粮", conversations.openConversation("v3-session").inputText)
        assertEquals("原有详情", schedules.taskForSession("v3-session")?.detail)
        assertNull(schedules.recentMessages("v3-session").single().occurrenceContextId)
        assertFalse(requireNotNull(database.scheduleDao().occurrence("v3-card")).isException)
        assertFalse(database.scheduleDao().isExcluded("v3-task", "date:20748"))
        assertEquals(listOf(10), schedules.reminderMinutesForSession("v3-session"))
        assertTrue(database.mediaDao().cleanupPaths(10).isEmpty())
    }

    companion object { private const val TEST_KEY = "local-model-fixture-ModelSettingsStorageTest-1" }
}
