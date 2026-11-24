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

    @Test fun immediateNextExerciseAfterSolveStillPersistsSolvedAttemptMoves() = runBlocking {
        val container = AppContainer.getInstance(InstrumentationRegistry.getInstrumentation().targetContext)
        val user = container.userRepository.save(User(nickname = "FastNext", email = "fastnext${System.nanoTime()}@test.local", passwordHash = "test"))
        val uid = requireNotNull(user.id)
        val game = container.gameRepository.save(Game(userId = uid, playerColor = ChessColor.WHITE, playedAt = 1L, pgnData = "1. e4 *"))
        val gid = requireNotNull(game.id)
        val puzzle = CuratedExerciseBank.puzzles.single { it.id == "back_rank_mate_1" }
        val repo = container.exerciseRepository
        val vm = TrainingViewModel(container)
        suspend fun awaitState(predicate: (TrainingUiState) -> Boolean) = withTimeout(15_000L) {
            while (!withContext(Dispatchers.Main) { predicate(vm.uiState.value) }) delay(10)
        }
        try {
            val savedEx = repo.saveExercise(
                puzzle.toExercise().copy(createdFrom = ExerciseSource.MISTAKE, sourceGameId = gid, sourceMoveNumber = 1)
            )
            withContext(Dispatchers.Main) { vm.init(user, TrainingMode.FromGame(gid, 1)) }
            awaitState { it.hasActiveExercise && !it.isInputLocked }
            withContext(Dispatchers.Main) { vm.onMoveMade(Move(Square.D1, Square.D8)) }
            awaitState { it.isSolved }
            // Simulate tapping "Следующая" immediately on the same frame `isSolved` becomes true.
            withContext(Dispatchers.Main) { vm.loadNextExercise() }
            awaitState { !it.isLoading }
            withTimeout(15_000L) {
                while (repo.getAttemptsByUserId(uid).isEmpty()) delay(20)
            }
            val attempt = repo.getAttemptsByUserId(uid).single()
            assertEquals(savedEx.id, attempt.exerciseId)
            assertTrue(attempt.solved)
            assertEquals(listOf("d1d8"), AttemptMetadata.extractActualMoves(attempt))
        } finally {
            vm.viewModelScope.cancel()
            repo.deleteBySourceGameId(gid)
            container.gameRepository.deleteById(gid)
            container.userRepository.deleteById(uid)
        }
    }

    @Test fun switchingModeAfterSolvedAndFreeAnalysisClearsTransientFlags() = runBlocking {
        val container = AppContainer.getInstance(InstrumentationRegistry.getInstrumentation().targetContext)
        val user = container.userRepository.save(User(nickname = "TransientReset", email = "treset${System.nanoTime()}@test.local", passwordHash = "test"))
        val uid = requireNotNull(user.id)
        val game = container.gameRepository.save(Game(userId = uid, playerColor = ChessColor.WHITE, playedAt = 1L, pgnData = "1. e4 *"))
        val gid = requireNotNull(game.id)
        val puzzle = CuratedExerciseBank.puzzles.single { it.id == "back_rank_mate_1" }
        val repo = container.exerciseRepository
        val vm = TrainingViewModel(container)
        suspend fun awaitState(predicate: (TrainingUiState) -> Boolean) = withTimeout(15_000L) {
            while (!withContext(Dispatchers.Main) { predicate(vm.uiState.value) }) delay(15)
        }
        try {
            repo.saveExercise(puzzle.toExercise().copy(createdFrom = ExerciseSource.MISTAKE, sourceGameId = gid, sourceMoveNumber = 1))
            withContext(Dispatchers.Main) { vm.init(user, TrainingMode.FromGame(gid, 1)) }
            awaitState { it.hasActiveExercise && !it.isInputLocked }
            withContext(Dispatchers.Main) { vm.onMoveMade(Move(Square.D1, Square.D8)) }
            awaitState { it.isSolved }
            withContext(Dispatchers.Main) { vm.onToggleFreeAnalysis() }
            awaitState { it.isFreeAnalysisMode }

            // Delete the source game mistakes/exercises then switch to MyMistakes so it hits NoSuitableExercises.
            repo.deleteBySourceGameId(gid)
            withContext(Dispatchers.Main) { vm.selectMode(TrainingMode.MyMistakes) }
            awaitState { !it.isLoading && it.mode == TrainingMode.MyMistakes }

            val after = withContext(Dispatchers.Main) { vm.uiState.value }
            assertTrue(!after.isSolved)
            assertTrue(!after.isSolutionRevealed)
            assertTrue(!after.isFreeAnalysisMode)
            assertTrue(!after.isAnalyzingPosition)
            assertTrue(!after.isAnimatingPunishment)
            assertTrue(!after.checkUnavailable)
            assertTrue(after.reviewSteps.isEmpty())
            assertTrue(after.lastRatingDelta == null)
        } finally {
            vm.viewModelScope.cancel()
            repo.deleteBySourceGameId(gid)
            container.gameRepository.deleteById(gid)
            container.userRepository.deleteById(uid)
        }
    }

    @Test fun revealSolutionClearsCheckUnavailableAndPendingRetry() = runBlocking {
        val container = AppContainer.getInstance(InstrumentationRegistry.getInstrumentation().targetContext)
        val user = container.userRepository.save(User(nickname = "RevealRetry", email = "revretry${System.nanoTime()}@test.local", passwordHash = "test"))
        val uid = requireNotNull(user.id)
        val game = container.gameRepository.save(Game(userId = uid, playerColor = ChessColor.WHITE, playedAt = 1L, pgnData = "1. e4 *"))
        val gid = requireNotNull(game.id)
        val puzzle = CuratedExerciseBank.puzzles.single { it.id == "back_rank_mate_1" }
        val repo = container.exerciseRepository
        val unavailableEngine = object : ChessEngine by container.chessEngine {
            override suspend fun init() = true
            override suspend fun getBestMoveWithLine(fen: String, depthLimit: Int): AnalysisLine? = null
        }
        val vm = TrainingViewModel(container, unavailableEngine)
        suspend fun awaitState(predicate: (TrainingUiState) -> Boolean) = withTimeout(15_000L) {
            while (!withContext(Dispatchers.Main) { predicate(vm.uiState.value) }) delay(15)
        }
        try {
            repo.saveExercise(puzzle.toExercise().copy(createdFrom = ExerciseSource.MISTAKE, sourceGameId = gid, sourceMoveNumber = 1))
            withContext(Dispatchers.Main) { vm.init(user, TrainingMode.FromGame(gid, 1)) }
            awaitState { it.hasActiveExercise && !it.isInputLocked }
            withContext(Dispatchers.Main) { vm.onMoveMade(Move(Square.A2, Square.A3)) }
            awaitState { it.checkUnavailable }

            withContext(Dispatchers.Main) { vm.onRevealSolution() }
            awaitState { it.isSolutionRevealed }
            val revealed = withContext(Dispatchers.Main) { vm.uiState.value }
            assertTrue(!revealed.checkUnavailable)
            assertTrue(revealed.isSolutionRevealed)

            // No-op because pendingCheck was cleared on reveal.
            withContext(Dispatchers.Main) { vm.returnFromMoveCheck() }
            assertTrue(withContext(Dispatchers.Main) { vm.uiState.value.isSolutionRevealed })
        } finally {
            vm.viewModelScope.cancel()
            repo.deleteBySourceGameId(gid)
            container.gameRepository.deleteById(gid)
            container.userRepository.deleteById(uid)
        }
    }
}
