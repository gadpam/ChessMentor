package com.example.chessmentor.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.example.chessmentor.data.engine.ChessEngine
import com.example.chessmentor.di.AppContainer
import com.example.chessmentor.domain.entity.AnalysisStatus
import com.example.chessmentor.domain.entity.ChessColor
import com.example.chessmentor.domain.entity.Game
import com.example.chessmentor.domain.entity.User
import com.example.chessmentor.domain.usecase.AnalyzeGameUseCase
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class InterruptedGameAnalysisTest {
    @Test fun newOwnerObservesRollbackAndDoesNotRestartUntilAsked() = runBlocking {
        val container = AppContainer.getInstance(InstrumentationRegistry.getInstrumentation().targetContext)
        val user = container.userRepository.save(User(nickname = "Lifecycle", email = "owner${System.nanoTime()}@test.local", passwordHash = "test"))
        val uid = requireNotNull(user.id)
        val gid = requireNotNull(container.gameRepository.save(Game(userId = uid, pgnData = "1. e4 *", playerColor = ChessColor.WHITE, playedAt = 1)).id)
        val calls = AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        val allowRollback = CompletableDeferred<Unit>()
        val engine = object : ChessEngine by container.chessEngine {
            override suspend fun init(): Boolean {
                if (calls.incrementAndGet() != 1) return false // explicit retry reached the engine
                entered.complete(Unit)
                try { awaitCancellation() } finally { withContext(NonCancellable) { allowRollback.await() } }
            }
        }
        val analysis = AnalyzeGameUseCase(container.gameRepository, container.mistakeRepository,
            container.analyzedMoveRepository, container.userRepository, engine, container.engineSettingsRepository)
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = GameViewModel(container, analysis) as T
        }
        val first = ViewModelStore()
        val second = ViewModelStore()
        fun model(store: ViewModelStore) = ViewModelProvider(store, factory)[GameViewModel::class.java]
        suspend fun awaitStatus(vm: GameViewModel, expected: AnalysisStatus) = withTimeout(15_000L) {
            while (!withContext(Dispatchers.Main) { vm.userGames.singleOrNull { it.id == gid }?.analysisStatus == expected }) delay(20)
        }
        try {
            val old = withContext(Dispatchers.Main) { model(first).also { it.setUser(user, persistSession = false) } }
            awaitStatus(old, AnalysisStatus.PENDING)
            withContext(Dispatchers.Main) { assertTrue(old.analyzeGameWithProgress(gid)) }
            withTimeout(15_000L) { entered.await() }
            val reopened = withContext(Dispatchers.Main) {
                assertSame(old, model(first)) // configuration change retains the store
                assertTrue(model(first).analyzeGameWithProgress(gid))
                first.clear() // cancellation is still unwinding when the new library opens
                model(second).also { it.setUser(user, persistSession = false) }
            }
            awaitStatus(reopened, AnalysisStatus.IN_PROGRESS)
            allowRollback.complete(Unit)
            awaitStatus(reopened, AnalysisStatus.PENDING)
            assertEquals(1, calls.get())
            withContext(Dispatchers.Main) { assertTrue(reopened.analyzeGameWithProgress(gid, requestedByLibrary = true)) }
            awaitStatus(reopened, AnalysisStatus.FAILED)
            assertEquals(2, calls.get())
        } finally {
            allowRollback.complete(Unit)
            withContext(Dispatchers.Main) { first.clear(); second.clear() }
            container.gameRepository.deleteById(gid)
            container.userRepository.deleteById(uid)
        }
    }
}
