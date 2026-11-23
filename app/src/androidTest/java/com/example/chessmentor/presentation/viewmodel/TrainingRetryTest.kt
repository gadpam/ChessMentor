package com.example.chessmentor.presentation.viewmodel

import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.example.chessmentor.data.engine.AnalysisLine
import com.example.chessmentor.data.engine.ChessEngine
import com.example.chessmentor.di.AppContainer
import com.example.chessmentor.domain.entity.ChessColor
import com.example.chessmentor.domain.entity.ExerciseSource
import com.example.chessmentor.domain.entity.Game
import com.example.chessmentor.domain.entity.User
import com.example.chessmentor.domain.training.AttemptMetadata
import com.example.chessmentor.domain.training.CuratedExerciseBank
import com.example.chessmentor.domain.training.TrainingMode
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainingRetryTest {
    @Test fun sameMoveCanBeRecheckedWithoutPenaltyOrDuplicateAttempt() = runBlocking {
        val container = AppContainer.getInstance(InstrumentationRegistry.getInstrumentation().targetContext)
        val user = container.userRepository.save(User(nickname = "RetryTest", email = "retry${System.nanoTime()}@test.local", passwordHash = "test"))
        val uid = requireNotNull(user.id)
        val game = container.gameRepository.save(Game(userId = uid, playerColor = ChessColor.WHITE, playedAt = 1L, pgnData = "1. e4 *"))
        val gid = requireNotNull(game.id)
        val puzzle = CuratedExerciseBank.puzzles.single { it.id == "back_rank_mate_1" }
        val repo = container.exerciseRepository
        // A scripted verdict for a legal non-book move; no JNI or network dependency.
        val engine = object : ChessEngine by container.chessEngine {
            var searches = 0
            override suspend fun init() = true
            override suspend fun getBestMoveWithLine(fen: String, depthLimit: Int): AnalysisLine? {
                assertEquals(puzzle.fen, fen)
                return if (++searches < 3) null else AnalysisLine("a2a3", 0, null, listOf("a2a3"))
            }
        }
        val vm = TrainingViewModel(container, engine)
        suspend fun awaitState(predicate: (TrainingUiState) -> Boolean) = withTimeout(15_000L) {
            while (!withContext(Dispatchers.Main) { predicate(vm.uiState.value) }) delay(20)
        }
        try {
            repo.saveExercise(puzzle.toExercise().copy(createdFrom = ExerciseSource.MISTAKE, sourceGameId = gid, sourceMoveNumber = 1))
            withContext(Dispatchers.Main) { vm.init(user, TrainingMode.FromGame(gid, 1)) }
            awaitState { it.hasActiveExercise && !it.isInputLocked }
            val move = Move(Square.A2, Square.A3)
            withContext(Dispatchers.Main) { vm.onMoveMade(move) }
            awaitState { it.checkUnavailable }
            withContext(Dispatchers.Main) {
                vm.returnFromMoveCheck()
                assertEquals(puzzle.fen, vm.uiState.value.board.fen)
                vm.onMoveMade(move)
            }
            awaitState { it.checkUnavailable }
            assertEquals(user.rating, container.userRepository.findById(uid)?.rating)
            assertTrue(repo.getAttemptsByUserId(uid).isEmpty())
            withContext(Dispatchers.Main) {
                vm.retryMoveCheck()
                vm.retryMoveCheck() // duplicate tap while evaluating
            }
            awaitState { it.isSolved && it.nextReviewNote != null }
            val attempt = repo.getAttemptsByUserId(uid).single()
            assertTrue(attempt.solved)
            assertEquals(listOf("a2a3"), AttemptMetadata.extractActualMoves(attempt))
            assertEquals(0, AttemptMetadata.extractRetryCount(attempt))
            assertEquals(3, engine.searches)
        } finally {
            vm.viewModelScope.cancel()
            repo.deleteBySourceGameId(gid)
            container.gameRepository.deleteById(gid)
            container.userRepository.deleteById(uid)
        }
    }

    @Test fun initAfterSessionSummaryWithExplicitModeClearsSummaryAndLoadsNewMode() = runBlocking {
        val container = AppContainer.getInstance(InstrumentationRegistry.getInstrumentation().targetContext)
        val user = container.userRepository.save(User(nickname = "ModeSwitch", email = "modeswitch${System.nanoTime()}@test.local", passwordHash = "test"))
        val uid = requireNotNull(user.id)
        val vm = TrainingViewModel(container)
        suspend fun awaitState(predicate: (TrainingUiState) -> Boolean) = withTimeout(15_000L) {
            while (!withContext(Dispatchers.Main) { predicate(vm.uiState.value) }) delay(20)
        }
        try {
            withContext(Dispatchers.Main) { vm.init(user, TrainingMode.SmartSession) }
            awaitState { !it.isLoading && it.hasActiveExercise }
            repeat(10) {
                withContext(Dispatchers.Main) { vm.onSkipExercise() }
                awaitState { !it.isLoading }
            }
            awaitState { it.sessionSummary != null }
            withContext(Dispatchers.Main) { vm.init(user, TrainingMode.Daily) }
            awaitState { !it.isLoading && it.sessionSummary == null && it.mode == TrainingMode.Daily }
            assertEquals(TrainingMode.Daily, vm.uiState.value.mode)
            assertTrue(vm.uiState.value.hasActiveExercise)

            withContext(Dispatchers.Main) { vm.showInitializationError("Ошибка теста") }
            val errState = withContext(Dispatchers.Main) { vm.uiState.value }
            assertTrue(errState.isInitializationError)
            assertTrue(!errState.isLoading)
            assertEquals("Ошибка теста", errState.feedbackMessage)
        } finally {
            vm.viewModelScope.cancel()
            container.userRepository.deleteById(uid)
        }
    }
}
