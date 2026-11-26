package com.example.chessmentor.presentation.viewmodel

import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.example.chessmentor.data.engine.AnalysisLine
import com.example.chessmentor.data.engine.ChessEngine
import com.example.chessmentor.di.AppContainer
import com.example.chessmentor.domain.entity.AnalysisStatus
import com.example.chessmentor.domain.entity.AnalyzedMove
import com.example.chessmentor.domain.entity.ChessColor
import com.example.chessmentor.domain.entity.Exercise
import com.example.chessmentor.domain.entity.ExerciseSource
import com.example.chessmentor.domain.entity.Game
import com.example.chessmentor.domain.entity.MoveQuality
import com.example.chessmentor.domain.entity.TacticalPattern
import com.example.chessmentor.domain.entity.TacticalTheme
import com.example.chessmentor.domain.entity.User
import com.example.chessmentor.domain.training.AttemptMetadata
import com.example.chessmentor.domain.training.CuratedExerciseBank
import com.example.chessmentor.domain.training.ExerciseMetadata
import com.example.chessmentor.domain.training.TrainingMode
import com.example.chessmentor.domain.util.ChessMoveUtils
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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

    @Test fun verificationEngineHandlesMistakeFallbackContinuationAndFreeAnalysis() = runBlocking {
        val container = AppContainer.getInstance(InstrumentationRegistry.getInstrumentation().targetContext)
        val user = container.userRepository.save(User(nickname = "VerifyEngine", email = "vengine${System.nanoTime()}@test.local", passwordHash = "test"))
        val uid = requireNotNull(user.id)
        val game = container.gameRepository.save(Game(userId = uid, playerColor = ChessColor.WHITE, playedAt = 1L, pgnData = "1. e4 *"))
        val gid = requireNotNull(game.id)
        val startFen = "4r1k1/5pp1/7p/8/Q7/8/4RPPP/4R1K1 w - - 0 1"
        val repo = container.exerciseRepository

        val badMove = Move(Square.H2, Square.H3)
        val badFen = Board().apply {
            loadFromFen(startFen)
            assertTrue(ChessMoveUtils.isStrictlyLegal(this, badMove))
            assertTrue(doMove(badMove))
        }.fen
        val altMove = Move(Square.A4, Square.E8)
        val altFen = Board().apply {
            loadFromFen(startFen)
            assertTrue(ChessMoveUtils.isStrictlyLegal(this, altMove))
            assertTrue(doMove(altMove))
        }.fen

        var badFenLineCalls = 0
        var fallbackGetBestMoveCalls = 0
        var altContinuationCalls = 0
        var freeAnalysisCalls = 0

        val fakeEngine = object : ChessEngine {
            override suspend fun init() = true
            override suspend fun getBestMoveWithLine(fen: String, depthLimit: Int): AnalysisLine? = when (fen) {
                startFen -> {
                    freeAnalysisCalls++
                    AnalysisLine("e2e8", 900, null, listOf("e2e8", "g8h7", "a4c2"))
                }
                badFen -> {
                    badFenLineCalls++
                    // First call verifies the mistake; returning null on the second routes into fallbackMistakeHandling.
                    if (badFenLineCalls == 1) AnalysisLine("e8e2", 500, null, listOf("e8e2")) else null
                }
                altFen -> {
                    altContinuationCalls++
                    AnalysisLine("g8h7", -880, null, listOf("g8h7", "e8e4"))
                }
                else -> null
            }
            override suspend fun evaluate(fen: String, depthLimit: Int): Int = 0
            override suspend fun getBestMove(fen: String, depthLimit: Int): String? {
                if (fen == badFen) {
                    fallbackGetBestMoveCalls++
                    return "e8e2"
                }
                return null
            }
            override suspend fun setOption(name: String, value: String) = Unit
            override fun stop() = Unit
            override fun destroy() = Unit
        }
        val vm = TrainingViewModel(container, fakeEngine)
        suspend fun awaitState(predicate: (TrainingUiState) -> Boolean) = withTimeout(15_000L) {
            while (!withContext(Dispatchers.Main) { predicate(vm.uiState.value) }) delay(20)
        }
        try {
            repo.saveExercise(
                Exercise(
                    fenPosition = startFen,
                    solutionMoves = listOf("e2e8", "g8h7", "a4c2"),
                    tacticalPattern = TacticalPattern.BACK_RANK_MATE,
                    patternClusterId = "BACK_RANK_MATE_MEDIUM",
                    difficultyRating = 45,
                    theme = TacticalTheme.TACTIC,
                    createdFrom = ExerciseSource.MISTAKE,
                    sourceGameId = gid,
                    sourceMoveNumber = 1
                )
            )
            withContext(Dispatchers.Main) { vm.init(user, TrainingMode.FromGame(gid, 1)) }
            awaitState { it.hasActiveExercise && !it.isInputLocked }

            // 1. Mistake -> handleMistakeComprehensive -> fallbackMistakeHandling via verificationEngine
            withContext(Dispatchers.Main) { vm.onMoveMade(badMove) }
            awaitState { it.isAnimatingPunishment }
            awaitState { !it.isAnimatingPunishment && !it.isInputLocked && it.board.fen == startFen }
            assertEquals(2, badFenLineCalls)
            assertEquals(1, fallbackGetBestMoveCalls)

            // 2. Alternative move a4e8 -> verifyTrainingMove + fetchEvenContinuationForOpponent via verificationEngine
            withContext(Dispatchers.Main) { vm.onMoveMade(altMove) }
            awaitState { !it.isInputLocked && it.lastMove == (Square.G8 to Square.H7) }
            assertTrue(altContinuationCalls >= 2)

            // 3. Finish line & trigger free analysis on starting position via verificationEngine
            withContext(Dispatchers.Main) { vm.onMoveMade(Move(Square.E8, Square.E4)) }
            awaitState { it.isSolved }
            val callsBeforeFreeAnalysis = freeAnalysisCalls
            withContext(Dispatchers.Main) {
                vm.onReviewFirst()
                vm.onToggleFreeAnalysis()
            }
            awaitState { it.isFreeAnalysisMode && !it.isAnalyzingPosition && it.analysisBestMoveUci == "e2e8" }
            assertTrue(freeAnalysisCalls > callsBeforeFreeAnalysis)
        } finally {
            vm.viewModelScope.cancel()
            repo.deleteBySourceGameId(gid)
            container.gameRepository.deleteById(gid)
            container.userRepository.deleteById(uid)
        }
    }

    @Test fun verificationEngineExtendsShortNonCuratedSolution() = runBlocking {
        val container = AppContainer.getInstance(InstrumentationRegistry.getInstrumentation().targetContext)
        val user = container.userRepository.save(User(nickname = "ExtendEngine", email = "ext${System.nanoTime()}@test.local", passwordHash = "test"))
        val uid = requireNotNull(user.id)
        val game = container.gameRepository.save(Game(userId = uid, playerColor = ChessColor.WHITE, playedAt = 1L, pgnData = "1. e4 *"))
        val gid = requireNotNull(game.id)
        val startFen = "r5k1/5ppp/8/8/8/8/3R1PPP/3R2K1 w - - 0 1"
        val repo = container.exerciseRepository
        var extensionCalls = 0
        val fakeEngine = object : ChessEngine {
            override suspend fun init() = true
            override suspend fun getBestMoveWithLine(fen: String, depthLimit: Int): AnalysisLine? {
                if (fen == startFen) {
                    extensionCalls++
                    return AnalysisLine("d2d8", ChessEngine.MATE_VALUE, 2, listOf("d2d8", "a8d8", "d1d8"))
                }
                return null
            }
            override suspend fun evaluate(fen: String, depthLimit: Int): Int = 0
            override suspend fun getBestMove(fen: String, depthLimit: Int): String? = null
            override suspend fun setOption(name: String, value: String) = Unit
            override fun stop() = Unit
            override fun destroy() = Unit
        }
        val vm = TrainingViewModel(container, fakeEngine)
        suspend fun awaitState(predicate: (TrainingUiState) -> Boolean) = withTimeout(15_000L) {
            while (!withContext(Dispatchers.Main) { predicate(vm.uiState.value) }) delay(20)
        }
        try {
            repo.saveExercise(
                Exercise(
                    fenPosition = startFen,
                    solutionMoves = listOf("d2d8"),
                    tacticalPattern = TacticalPattern.BACK_RANK_MATE,
                    patternClusterId = "BACK_RANK_MATE_EASY",
                    difficultyRating = 25,
                    theme = TacticalTheme.MATE,
                    createdFrom = ExerciseSource.MISTAKE,
                    sourceGameId = gid,
                    sourceMoveNumber = 1
                )
            )
            withContext(Dispatchers.Main) { vm.init(user, TrainingMode.FromGame(gid, 1)) }
            awaitState { it.hasActiveExercise && !it.isInputLocked }
            assertEquals(1, extensionCalls)
            assertEquals(2, withContext(Dispatchers.Main) { vm.uiState.value.totalPlayerSteps })
        } finally {
            vm.viewModelScope.cancel()
            repo.deleteBySourceGameId(gid)
            container.gameRepository.deleteById(gid)
            container.userRepository.deleteById(uid)
        }
    }

    @Test fun corruptedOpponentStepRollsBackWithoutFalseWinOrRating() = runBlocking {
        val container = AppContainer.getInstance(InstrumentationRegistry.getInstrumentation().targetContext)
        val user = container.userRepository.save(User(nickname = "CorruptSeq", email = "corrupt${System.nanoTime()}@test.local", passwordHash = "test"))
        val uid = requireNotNull(user.id)
        val game = container.gameRepository.save(Game(userId = uid, playerColor = ChessColor.BLACK, playedAt = 1L, pgnData = "1. a3 *"))
        val gid = requireNotNull(game.id)
        val puzzle = CuratedExerciseBank.puzzles.single { it.id == "back_rank_black_2" }
        val repo = container.exerciseRepository
        val vm = TrainingViewModel(container)
        suspend fun awaitState(predicate: (TrainingUiState) -> Boolean) = withTimeout(15_000L) {
            while (!withContext(Dispatchers.Main) { predicate(vm.uiState.value) }) delay(20)
        }
        try {
            repo.saveExercise(puzzle.toExercise().copy(createdFrom = ExerciseSource.MISTAKE, sourceGameId = gid, sourceMoveNumber = 1))
            withContext(Dispatchers.Main) { vm.init(user, TrainingMode.FromGame(gid, 1)) }
            awaitState { it.hasActiveExercise && !it.isAnimatingIntro && !it.isInputLocked }

            withContext(Dispatchers.Main) {
                vm.overrideTargetSequenceForTest(listOf("d7d1", "e1e8", "d8d1"))
                vm.onMoveMade(Move(Square.D7, Square.D1))
            }
            awaitState { !it.isInputLocked && it.board.fen == puzzle.fen && it.feedbackMessage?.contains("Не удалось применить ответ соперника") == true }

            val state = withContext(Dispatchers.Main) { vm.uiState.value }
            assertTrue(!state.isSolved)
            assertEquals(puzzle.fen, state.board.fen)
            assertEquals(user.rating, container.userRepository.findById(uid)?.rating)
            assertTrue(repo.getAttemptsByUserId(uid).isEmpty())
        } finally {
            vm.viewModelScope.cancel()
            repo.deleteBySourceGameId(gid)
            container.gameRepository.deleteById(gid)
            container.userRepository.deleteById(uid)
        }
    }

    @Test fun pauseDuringIntroAnimationPreservesPausedAndLockedStateAfterIntro() = runBlocking {
        val container = AppContainer.getInstance(InstrumentationRegistry.getInstrumentation().targetContext)
        val user = container.userRepository.save(User(nickname = "IntroPause", email = "intropause${System.nanoTime()}@test.local", passwordHash = "test"))
        val uid = requireNotNull(user.id)
        val game = container.gameRepository.save(Game(userId = uid, playerColor = ChessColor.BLACK, playedAt = 1L, pgnData = "1. a3 *"))
        val gid = requireNotNull(game.id)
        val puzzle = CuratedExerciseBank.puzzles.single { it.id == "back_rank_black_2" }
        val repo = container.exerciseRepository
        val vm = TrainingViewModel(container)
        suspend fun awaitFast(predicate: (TrainingUiState) -> Boolean) = withTimeout(15_000L) {
            while (!withContext(Dispatchers.Main) { predicate(vm.uiState.value) }) delay(5)
        }
        try {
            repo.saveExercise(puzzle.toExercise().copy(createdFrom = ExerciseSource.MISTAKE, sourceGameId = gid, sourceMoveNumber = 1))
            withContext(Dispatchers.Main) { vm.init(user, TrainingMode.FromGame(gid, 1)) }
            awaitFast { it.isAnimatingIntro }
            withContext(Dispatchers.Main) { vm.onPauseTraining(nowMs = 1_000_000L) }
            awaitFast { !it.isAnimatingIntro }

            val pausedState = withContext(Dispatchers.Main) { vm.uiState.value }
            assertTrue(pausedState.isPaused)
            assertTrue(pausedState.isInputLocked)
            assertEquals(puzzle.fen, pausedState.board.fen)

            withContext(Dispatchers.Main) { vm.onResumeTraining(nowMs = 1_120_000L) }
            val resumedState = withContext(Dispatchers.Main) { vm.uiState.value }
            assertTrue(!resumedState.isPaused)
            assertTrue(!resumedState.isInputLocked)
        } finally {
            vm.viewModelScope.cancel()
            repo.deleteBySourceGameId(gid)
            container.gameRepository.deleteById(gid)
            container.userRepository.deleteById(uid)
        }
    }

    @Test fun skillTransferExhaustedUnseenPoolDoesNotFallbackToGeneralQueue() = runBlocking {
        val container = AppContainer.getInstance(InstrumentationRegistry.getInstrumentation().targetContext)
        val user = container.userRepository.save(User(nickname = "TransferVM", email = "transfervm${System.nanoTime()}@test.local", passwordHash = "test"))
        val uid = requireNotNull(user.id)
        val sourceGame = container.gameRepository.save(Game(userId = uid, playerColor = ChessColor.WHITE, playedAt = 1L, pgnData = "1. e4 *"))
        val otherGame = container.gameRepository.save(Game(userId = uid, playerColor = ChessColor.WHITE, playedAt = 2L, pgnData = "1. d4 *"))
        val sourceGid = requireNotNull(sourceGame.id)
        val otherGid = requireNotNull(otherGame.id)
        val repo = container.exerciseRepository
        val vm = TrainingViewModel(container)
        suspend fun awaitState(predicate: (TrainingUiState) -> Boolean) = withTimeout(15_000L) {
            while (!withContext(Dispatchers.Main) { predicate(vm.uiState.value) }) delay(20)
        }
        try {
            CuratedExerciseBank.ensureSeeded(repo)
            val allAccessible = repo.findAccessibleByUserId(uid)
            for (ex in allAccessible.filter { it.theme == TacticalTheme.FORK }) {
                val id = ex.id ?: continue
                repo.saveAttempt(
                    com.example.chessmentor.domain.entity.ExerciseAttempt(
                        userId = uid,
                        exerciseId = id,
                        solved = true,
                        timeSpentSeconds = 4,
                        movesMade = listOf(ex.solutionMoves.firstOrNull() ?: "a1a2"),
                        attemptedAt = 10_000L
                    )
                )
            }
            val unseenOtherFork = repo.saveExercise(
                Exercise(
                    fenPosition = "r5k1/5ppp/8/8/8/8/4RPPP/6K1 w - - 0 1",
                    solutionMoves = listOf("e2e8"),
                    tacticalPattern = TacticalPattern.FORK_KNIGHT,
                    patternClusterId = "FORK_KNIGHT_EASY",
                    difficultyRating = 35,
                    theme = TacticalTheme.FORK,
                    createdFrom = ExerciseSource.MISTAKE,
                    sourceGameId = otherGid,
                    sourceMoveNumber = 4
                )
            )
            // Даже при наличии нетронутых задач в общем банке (MATE/PIN/DEFENSE) перенос должен
            // отдать только незнакомую задачу этого навыка (unseenOtherFork), а при next — завершиться честно.
            val mode = TrainingMode.SkillTransfer(
                pattern = TacticalPattern.FORK_KNIGHT,
                theme = TacticalTheme.FORK,
                isDefensive = false,
                excludeSourceGameId = sourceGid
            )
            withContext(Dispatchers.Main) { vm.init(user, mode) }
            awaitState { !it.isLoading && it.hasActiveExercise && !it.isInputLocked }

            val loaded = withContext(Dispatchers.Main) { vm.uiState.value }
            assertEquals(mode, loaded.mode)
            assertEquals(unseenOtherFork.fenPosition, loaded.board.fen)

            withContext(Dispatchers.Main) { vm.loadNextExercise() }
            awaitState { !it.isLoading }

            val afterExhausted = withContext(Dispatchers.Main) { vm.uiState.value }
            assertTrue(!afterExhausted.hasActiveExercise)
            assertTrue(afterExhausted.feedbackMessage?.contains("проверили навык") == true)
        } finally {
            vm.viewModelScope.cancel()
            repo.deleteBySourceGameId(sourceGid)
            repo.deleteBySourceGameId(otherGid)
            container.gameRepository.deleteById(sourceGid)
            container.gameRepository.deleteById(otherGid)
            container.userRepository.deleteById(uid)
        }
    }

    @Test fun threeWrongMoves_capsZeroPenaltyGuessingAndRevealsSolution() = runBlocking {
        val container = AppContainer.getInstance(InstrumentationRegistry.getInstrumentation().targetContext)
        val user = container.userRepository.save(User(nickname = "CapGuess", email = "capguess${System.nanoTime()}@test.local", passwordHash = "test"))
        val uid = requireNotNull(user.id)
        val game = container.gameRepository.save(Game(userId = uid, playerColor = ChessColor.WHITE, playedAt = 1L, pgnData = "1. e4 *"))
        val gid = requireNotNull(game.id)
        val puzzle = CuratedExerciseBank.puzzles.single { it.id == "back_rank_mate_1" }
        val repo = container.exerciseRepository
        val wrongEngine = object : ChessEngine by container.chessEngine {
            override suspend fun init() = true
            override suspend fun getBestMoveWithLine(fen: String, depthLimit: Int): AnalysisLine? =
                if (fen == puzzle.fen) {
                    AnalysisLine("d1d8", ChessEngine.MATE_VALUE - 100, 1, listOf("d1d8"))
                } else {
                    AnalysisLine("a7d7", 50, null, emptyList())
                }
            override suspend fun getBestMove(fen: String, depthLimit: Int): String? = "a7d7"
        }
        val vm = TrainingViewModel(container, wrongEngine)
        suspend fun awaitState(predicate: (TrainingUiState) -> Boolean) = withTimeout(25_000L) {
            while (!withContext(Dispatchers.Main) { predicate(vm.uiState.value) }) delay(20)
        }
        try {
            repo.saveExercise(puzzle.toExercise().copy(createdFrom = ExerciseSource.MISTAKE, sourceGameId = gid, sourceMoveNumber = 1))
            withContext(Dispatchers.Main) { vm.init(user, TrainingMode.FromGame(gid, 1)) }
            awaitState { it.hasActiveExercise && !it.isInputLocked }

            // 1-я и 2-я ошибки оставляют доску открытой для повторного расчёта
            withContext(Dispatchers.Main) { vm.onMoveMade(Move(Square.A2, Square.A3)) }
            awaitState { !it.isAnimatingPunishment && !it.isInputLocked }
            withContext(Dispatchers.Main) { vm.onMoveMade(Move(Square.A2, Square.A4)) }
            awaitState { !it.isAnimatingPunishment && !it.isInputLocked }

            // 3-я ошибка останавливает бесконечный перебор и раскрывает решение с блокировкой доски
            withContext(Dispatchers.Main) { vm.onMoveMade(Move(Square.H2, Square.H3)) }
            awaitState { it.isSolutionRevealed && !it.isAnimatingPunishment }

            val capped = withContext(Dispatchers.Main) { vm.uiState.value }
            assertTrue(capped.isSolutionRevealed)
            assertTrue(capped.isInputLocked)
            assertFalse(capped.isSolved)
            assertEquals(3, capped.sessionStats.failed)
            assertEquals(0, capped.sessionStats.solved)
            // Попытка сыграть 4-й ход после раскрытия игнорируется
            val boardFenBeforeExtra = capped.board.fen
            withContext(Dispatchers.Main) { vm.onMoveMade(Move(Square.D1, Square.D8)) }
            assertEquals(boardFenBeforeExtra, withContext(Dispatchers.Main) { vm.uiState.value.board.fen })
            assertFalse(withContext(Dispatchers.Main) { vm.uiState.value.isSolved })
        } finally {
            vm.viewModelScope.cancel()
            repo.deleteBySourceGameId(gid)
            container.gameRepository.deleteById(gid)
            container.userRepository.deleteById(uid)
        }
    }

    @Test fun fullHintCompletion_doesNotIncrementSolvedOrEmitSolvedSound() = runBlocking {
        val container = AppContainer.getInstance(InstrumentationRegistry.getInstrumentation().targetContext)
        val user = container.userRepository.save(User(nickname = "FullHintHonest", email = "fhh${System.nanoTime()}@test.local", passwordHash = "test"))
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

            withContext(Dispatchers.Main) {
                vm.onHintClicked()
                vm.onHintClicked() // FULL_SOLUTION arrow
                vm.onMoveMade(Move(Square.D1, Square.D8))
            }
            awaitState { it.isSolved }

            val state = withContext(Dispatchers.Main) { vm.uiState.value }
            assertEquals(0, state.sessionStats.solved)
            assertEquals(1, state.sessionStats.failed)
            assertNotEquals(TrainingSoundEvent.SOLVED, state.soundEvent)

            withTimeout(10_000L) {
                while (repo.getAttemptsByUserId(uid).isEmpty()) delay(20)
            }
            val saved = repo.getAttemptsByUserId(uid).single()
            assertFalse(saved.solved)
            assertFalse(AttemptMetadata.isIndependentSolve(saved))
        } finally {
            vm.viewModelScope.cancel()
            repo.deleteBySourceGameId(gid)
            container.gameRepository.deleteById(gid)
            container.userRepository.deleteById(uid)
        }
    }

    @Test fun boardViewModel_engineFailureDoesNotIncrementAttempts_andRejectsSlowerMateAlternative() = runBlocking {
        val vm = BoardViewModel()
        val pgn = "1. e4 e5 2. Nf3 Nc6 3. a3 Bc5 *"
        val analyzed = AnalyzedMove(
            gameId = 1L,
            moveIndex = 4,
            moveNumber = 3,
            color = ChessColor.WHITE,
            quality = MoveQuality.MISTAKE,
            san = "a3",
            bestMove = "Bb5",
            evalBefore = 30,
            evalAfter = -20,
            evalLoss = 50,
            comment = "Неточность. Сильнее: 3. Bb5."
        )
        val game = Game(
            id = 1L,
            userId = 1L,
            pgnData = pgn,
            playerColor = ChessColor.WHITE,
            playedAt = 1L,
            analysisStatus = AnalysisStatus.COMPLETED
        )

        suspend fun awaitIdleRetry() = withTimeout(10_000L) {
            while (withContext(Dispatchers.Main) { vm.retryState.value?.isCheckingEngine == true }) {
                delay(15)
            }
        }

        val unavailableEngine = object : ChessEngine {
            override suspend fun init() = false
            override suspend fun evaluate(fen: String, depthLimit: Int) = 0
            override suspend fun getBestMove(fen: String, depthLimit: Int): String? = null
            override suspend fun getBestMoveWithLine(fen: String, depthLimit: Int): AnalysisLine? = null
            override suspend fun setOption(name: String, value: String) = Unit
            override fun stop() = Unit
            override fun destroy() = Unit
        }
        val slowerMateEngine = object : ChessEngine by unavailableEngine {
            override suspend fun init() = true
            override suspend fun getBestMoveWithLine(fen: String, depthLimit: Int): AnalysisLine {
                // Лучший (3. Bb5 -> .../1B2p3/...) оставляет сопернику -М1 (-99900), альтернатива (3. Bc4) оставляет -М2 (-99800)
                return if (fen.contains("/1B2p3/")) {
                    AnalysisLine("a7a6", -(ChessEngine.MATE_VALUE - 100), -1, listOf("a7a6"))
                } else {
                    AnalysisLine("a7a6", -(ChessEngine.MATE_VALUE - 200), -2, listOf("a7a6"))
                }
            }
        }

        try {
            withContext(Dispatchers.Main) {
                vm.loadGame(game, emptyList(), listOf(analyzed), listOf(20, 20, 25, 25, 30, -20, -20))
                vm.goToMove(4)
                vm.startRetryCurrentMove()
                vm.submitRetryMove(Move(Square.F1, Square.C4), engine = unavailableEngine)
            }
            awaitIdleRetry()
            val afterUnavailable = withContext(Dispatchers.Main) { vm.retryState.value!! }
            assertEquals(0, afterUnavailable.attemptsCount)
            assertFalse(afterUnavailable.isSolved)
            assertTrue(afterUnavailable.feedback.contains("попытка не засчитана"))

            withContext(Dispatchers.Main) {
                vm.submitRetryMove(Move(Square.F1, Square.C4), engine = slowerMateEngine)
            }
            awaitIdleRetry()
            val afterSlowerMate = withContext(Dispatchers.Main) { vm.retryState.value!! }
            assertEquals(1, afterSlowerMate.attemptsCount)
            assertFalse(afterSlowerMate.isSolved)
            assertNotNull(afterSlowerMate.feedback)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test fun smartSession_tracksSkippedStepsAndResetsCountersOnRestart_andMultiPlyDetectsImpulsiveBlunder() = runBlocking {
        val container = AppContainer.getInstance(InstrumentationRegistry.getInstrumentation().targetContext)
        val user = container.userRepository.save(User(nickname = "SmartW10", email = "sw10_${System.nanoTime()}@test.local", passwordHash = "test"))
        val uid = requireNotNull(user.id)
        val game = container.gameRepository.save(Game(userId = uid, playerColor = ChessColor.BLACK, playedAt = 1L, pgnData = "1. e4 *"))
        val gid = requireNotNull(game.id)
        val repo = container.exerciseRepository

        // 1. SmartSession tracking: skip step 1 -> step 2 reflects SKIPPED + CURRENT, restart clears counters
        val sessionVm = TrainingViewModel(container)
        suspend fun awaitSession(predicate: (TrainingUiState) -> Boolean) = withTimeout(15_000L) {
            while (!withContext(Dispatchers.Main) { predicate(sessionVm.uiState.value) }) delay(15)
        }
        try {
            withContext(Dispatchers.Main) { sessionVm.init(user, TrainingMode.SmartSession) }
            awaitSession { it.hasActiveExercise && !it.isInputLocked && it.sessionStepNumber == 1 }
            val step1Initial = withContext(Dispatchers.Main) { sessionVm.uiState.value }
            assertEquals(0, step1Initial.sessionCleanCount)
            assertEquals(0, step1Initial.sessionSkippedCount)
            assertEquals(SmartSessionStepStatus.CURRENT, step1Initial.sessionStepStatuses.firstOrNull())

            withContext(Dispatchers.Main) { sessionVm.onSkipExercise() }
            awaitSession { it.hasActiveExercise && !it.isInputLocked && it.sessionStepNumber == 2 }
            val step2State = withContext(Dispatchers.Main) { sessionVm.uiState.value }
            assertEquals(0, step2State.sessionCleanCount)
            assertEquals(1, step2State.sessionSkippedCount)
            assertEquals(SmartSessionStepStatus.SKIPPED, step2State.sessionStepStatuses[0])
            assertEquals(SmartSessionStepStatus.CURRENT, step2State.sessionStepStatuses[1])

            withContext(Dispatchers.Main) { sessionVm.restartSmartSession() }
            awaitSession { it.hasActiveExercise && !it.isInputLocked && it.sessionStepNumber == 1 }
            val restarted = withContext(Dispatchers.Main) { sessionVm.uiState.value }
            assertEquals(0, restarted.sessionCleanCount)
            assertEquals(0, restarted.sessionFailedCount)
            assertEquals(0, restarted.sessionSkippedCount)
            assertEquals(SmartSessionStepStatus.CURRENT, restarted.sessionStepStatuses[0])
        } finally {
            sessionVm.viewModelScope.cancel()
        }

        // 2. Multi-ply impulsive blunder detection on ply 2 after deliberate ply 0
        val puzzle = CuratedExerciseBank.puzzles.single { it.id == "back_rank_black_2" }
        val blunderEngine = object : ChessEngine by container.chessEngine {
            override suspend fun init() = true
            override suspend fun getBestMoveWithLine(fen: String, depthLimit: Int): AnalysisLine {
                return if (fen.contains(" w ")) {
                    AnalysisLine("a3a4", 500, null, listOf("a3a4"))
                } else {
                    AnalysisLine("d8d1", ChessEngine.MATE_VALUE - 100, 1, listOf("d8d1"))
                }
            }
        }
        val plyVm = TrainingViewModel(container, blunderEngine)
        suspend fun awaitPly(predicate: (TrainingUiState) -> Boolean) = withTimeout(15_000L) {
            while (!withContext(Dispatchers.Main) { predicate(plyVm.uiState.value) }) delay(10)
        }
        try {
            repo.saveExercise(puzzle.toExercise().copy(createdFrom = ExerciseSource.MISTAKE, sourceGameId = gid, sourceMoveNumber = 1))
            withContext(Dispatchers.Main) { plyVm.init(user, TrainingMode.FromGame(gid, 1)) }
            awaitPly { it.hasActiveExercise && !it.isInputLocked && it.currentStepNumber == 1 }

            withContext(Dispatchers.Main) { plyVm.onMoveMade(Move(Square.D7, Square.D1)) }
            awaitPly { it.currentStepNumber == 2 && !it.isInputLocked }

            // Play instant blunder immediately after opponent reply 2. Rxd1
            withContext(Dispatchers.Main) { plyVm.onMoveMade(Move(Square.H7, Square.H6)) }
            awaitPly { it.sessionStats.failed == 1 && !it.isAnimatingPunishment }

            val afterBlunder = withContext(Dispatchers.Main) { plyVm.uiState.value }
            assertEquals(1, afterBlunder.sessionStats.impulsiveMistakes)

            withTimeout(10_000L) {
                while (repo.getAttemptsByUserId(uid).isEmpty()) delay(20)
            }
            val saved = repo.getAttemptsByUserId(uid).single()
            assertFalse(saved.solved)
            assertTrue(AttemptMetadata.isImpulsive(saved))
        } finally {
            plyVm.viewModelScope.cancel()
            repo.deleteBySourceGameId(gid)
            container.gameRepository.deleteById(gid)
            container.userRepository.deleteById(uid)
        }
    }
}
