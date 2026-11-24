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

    @Test fun seedSampleLibraryIfEmptyInsertsThreeGamesIdempotently() = runBlocking {
        val tester = requireNotNull(profiles.open(onlyIfEmpty = true))
        val uid = requireNotNull(tester.id)
        assertEquals(3, profiles.seedSampleLibraryIfEmpty(uid))
        val games = db.gameDao().findByUserId(uid)
        assertEquals(3, games.size)
        assertEquals(2, games.count { it.isAnalyzed() })
        assertEquals(2, db.mistakeDao().findByGameIds(games.mapNotNull { it.id }).size)
        assertEquals(1, db.exerciseDao().getAttemptsByUserId(uid).size)
        assertEquals(0, profiles.seedSampleLibraryIfEmpty(uid))
        assertEquals(3, db.gameDao().findByUserId(uid).size)
    }
}
