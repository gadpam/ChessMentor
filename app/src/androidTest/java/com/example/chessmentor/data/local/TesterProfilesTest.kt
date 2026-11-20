package com.example.chessmentor.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.chessmentor.domain.entity.User
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TesterProfilesTest {
    private val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,
        AppDatabase::class.java).build()
    private val profiles = TesterProfiles(db)
    @After fun close() = db.close()

    @Test fun firstLaunchCreatesOneTesterAndManualReentryReusesIt() = runBlocking {
        val initial = requireNotNull(profiles.open(onlyIfEmpty = true))
        assertNull(profiles.open(onlyIfEmpty = true))
        assertEquals(initial, profiles.open(onlyIfEmpty = false))
    }

    @Test fun ordinaryAccountIsNeverSelectedByTesterEntry() = runBlocking {
        val id = db.userDao().insert(User(nickname = "Тестировщик", email = "test@example.com", passwordHash = "normal-hash"))
        assertNull(profiles.open(onlyIfEmpty = true))
        val tester = requireNotNull(profiles.open(onlyIfEmpty = false))
        assertNotEquals(id, tester.id)
        assertEquals("normal-hash", db.userDao().findById(id)?.passwordHash)
    }
}
