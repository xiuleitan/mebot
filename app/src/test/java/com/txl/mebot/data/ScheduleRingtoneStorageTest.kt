package com.txl.mebot.data

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.local.MebotDatabase
import com.txl.mebot.data.local.MessageRole
import com.txl.mebot.data.local.ScheduleNotificationSettingsEntity
import com.txl.mebot.data.schedule.ScheduleDraft
import com.txl.mebot.data.schedule.ScheduleRepository
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
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
class ScheduleRingtoneStorageTest {
    private lateinit var context: Context
    private lateinit var database: MebotDatabase
    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(MebotDatabase.FILE_NAME)
        File(context.filesDir, "ringtones").deleteRecursively()
        database = MebotDatabase.create(context)
    }

    @After fun tearDown() {
        database.close()
        context.deleteDatabase(MebotDatabase.FILE_NAME)
        File(context.filesDir, "ringtones").deleteRecursively()
    }

    @Test fun existingRingtoneRecordAndAudioStayReadable() = runBlocking {
        val directory = File(context.filesDir, "ringtones").apply { mkdirs() }
        val legacyAudio = File(directory, "previous.flac").apply { writeText("legacy audio fixture") }
        val saved = ScheduleNotificationSettingsEntity(
            ringtonePath = "ringtones/previous.flac",
            ringtoneName = "旧铃声.flac",
            byteCount = legacyAudio.length(),
            durationMillis = 1_000,
            updatedAt = 123,
        )
        database.reminderDao().saveSettings(saved)

        database.close()
        database = MebotDatabase.create(context)
        assertEquals(saved, database.reminderDao().settings())
        assertEquals("legacy audio fixture", legacyAudio.readText())
    }

    @Test fun migrationFromSevenPreservesTasksMessagesAndReminders() = runBlocking {
        val schedules = ScheduleRepository(database)
        val session = schedules.openSession()
        schedules.appendMessage(session.id, "user", MessageRole.USER, "未来的会议")
        schedules.createTask(session.id, "create", ScheduleDraft("会议", date = LocalDate.of(2099, 1, 1),
            time = LocalTime.of(9, 0), reminderMinutes = listOf(0)))
        val occurrence = schedules.observeDate(LocalDate.of(2099, 1, 1)).first().single()
        val reminder = database.scheduleDao().reminders(occurrence.id).single()
        database.close()
        val schema = File("schemas/com.txl.mebot.data.local.MebotDatabase/7.json").let {
            if (it.exists()) it else File("app/schemas/com.txl.mebot.data.local.MebotDatabase/7.json")
        }
        val oldSchema = JSONObject(schema.readText()).getJSONObject("database")
        val hash = oldSchema.getString("identityHash")
        val entities = oldSchema.getJSONArray("entities")
        val oldTables = (0 until entities.length()).map { entities.getJSONObject(it).getString("tableName") }.toSet()
        SQLiteDatabase.openDatabase(context.getDatabasePath(MebotDatabase.FILE_NAME).path, null, SQLiteDatabase.OPEN_READWRITE).use {
            // 按版本 7 的清单保留旧表，避免新 Bot 的表混进旧库并掩盖迁移问题。
            val tables = it.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name NOT IN ('room_master_table','android_metadata')", null).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            tables.filterNot { table -> table in oldTables }.forEach { table -> it.execSQL("DROP TABLE `$table`") }
            it.execSQL("UPDATE room_master_table SET identity_hash = ? WHERE id = 42", arrayOf(hash))
            it.version = 7
        }
        database = MebotDatabase.create(context)
        assertNull(database.reminderDao().settings())
        assertEquals("会议", database.scheduleDao().occurrence(occurrence.id)?.title)
        assertEquals(reminder, database.scheduleDao().reminders(occurrence.id).single())
        assertEquals("未来的会议", database.scheduleDao().message("user")?.content)
    }
}
