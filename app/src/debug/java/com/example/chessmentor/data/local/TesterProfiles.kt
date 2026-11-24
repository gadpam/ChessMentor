package com.example.chessmentor.data.local

import androidx.room.withTransaction
import com.example.chessmentor.BuildConfig
import com.example.chessmentor.domain.entity.AnalysisStatus
import com.example.chessmentor.domain.entity.AnalyzedMove
import com.example.chessmentor.domain.entity.ChessColor
import com.example.chessmentor.domain.entity.Exercise
import com.example.chessmentor.domain.entity.ExerciseAttempt
import com.example.chessmentor.domain.entity.ExerciseSource
import com.example.chessmentor.domain.entity.Game
import com.example.chessmentor.domain.entity.Mistake
import com.example.chessmentor.domain.entity.MistakeType
import com.example.chessmentor.domain.entity.MoveQuality
import com.example.chessmentor.domain.entity.TacticalPattern
import com.example.chessmentor.domain.entity.TacticalTheme
import com.example.chessmentor.domain.entity.User
import java.util.UUID

/**
 * Local debug persona and sample library seeder.
 * Excluded from release source set; this marker is not a password hash.
 */
internal class TesterProfiles(private val db: AppDatabase) {
    suspend fun open(onlyIfEmpty: Boolean): User? {
        if (!BuildConfig.DEBUG) return null
        return db.withTransaction {
            val users = db.userDao()
            if (onlyIfEmpty && users.hasUsers()) return@withTransaction null
            val marker = "local-debug-persona:v1"
            users.findPasswordlessProfile(marker) ?: run {
                val token = UUID.randomUUID().toString()
                val tester = User(
                    nickname = "Тестировщик-${token.take(6)}",
                    email = "$token@example.invalid",
                    passwordHash = marker
                )
                tester.copy(id = users.insert(tester))
            }
        }
    }

    /**
     * Populates a debug user's empty library with realistic analyzed/pending games,
     * mistakes, personal tactical exercises, and attempts for instant UI testing
     * without waiting for NNUE download or Stockfish analysis.
     */
    suspend fun seedSampleLibraryIfEmpty(userId: Long): Int {
        if (!BuildConfig.DEBUG || userId <= 0L) return 0
        return db.withTransaction {
            val gameDao = db.gameDao()
            if (gameDao.findByUserId(userId).isNotEmpty()) return@withTransaction 0

            val now = System.currentTimeMillis()
            val dayMs = 86_400_000L

            val game1Id = gameDao.insert(
                Game(
                    userId = userId,
                    pgnData = """
                        [Event "Debug Sample Rapid"]
                        [Site "ChessMentor Debug"]
                        [White "Тестировщик"]
                        [Black "DebugRival-Alpha"]
                        [Result "1-0"]
                        [ECO "C50"]
                        [Opening "Italian Game"]
                        [TimeControl "600+0"]

                        1. e4 e5 2. Nf3 Nc6 3. Bc4 Bc5 4. c3 Nf6 5. d4 exd4 6. cxd4 Bb4+ 7. Nc3 Nxe4 8. O-O Bxc3 9. bxc3 d5 1-0
                    """.trimIndent(),
                    playerColor = ChessColor.WHITE,
                    timeControl = "600+0",
                    playedAt = now - 3 * dayMs,
                    analysisStatus = AnalysisStatus.COMPLETED,
                    analyzedAt = now - 3 * dayMs + 60_000L,
                    accuracy = 87.4,
                    averageEvaluationLoss = 24,
                    totalMistakes = 1,
                    blundersCount = 0,
                    mistakesCount = 1,
                    inaccuraciesCount = 0,
                    evaluationsJson = "[20,25,30,22,28,24,18,15,35,30,40,38,-95,120,160,155,190,210,225]"
                )
            )
            db.analyzedMoveDao().insertAll(
                listOf(
                    AnalyzedMove(gameId = game1Id, moveIndex = 0, moveNumber = 1, color = ChessColor.WHITE, quality = MoveQuality.BOOK, san = "e4", bestMove = "e4", evalBefore = 20, evalAfter = 25, evalLoss = 0),
                    AnalyzedMove(gameId = game1Id, moveIndex = 1, moveNumber = 1, color = ChessColor.BLACK, quality = MoveQuality.BOOK, san = "e5", bestMove = "e5", evalBefore = 25, evalAfter = 30, evalLoss = 0),
                    AnalyzedMove(gameId = game1Id, moveIndex = 11, moveNumber = 6, color = ChessColor.BLACK, quality = MoveQuality.BEST_MOVE, san = "Bb4+", bestMove = "Bb4+", evalBefore = 40, evalAfter = 38, evalLoss = 0),
                    AnalyzedMove(
                        gameId = game1Id,
                        moveIndex = 12,
                        moveNumber = 7,
                        color = ChessColor.WHITE,
                        quality = MoveQuality.MISTAKE,
                        san = "Nc3",
                        bestMove = "Bd2",
                        evalBefore = 38,
                        evalAfter = -95,
                        evalLoss = 133,
                        comment = "7. Bd2 защищает от связки надёжнее, не отдавая пешку e4."
                    )
                )
            )
            db.mistakeDao().insert(
                Mistake(
                    gameId = game1Id,
                    themeId = 10L,
                    moveNumber = 7,
                    color = ChessColor.WHITE,
                    evaluationLoss = 133,
                    mistakeType = MistakeType.MISTAKE,
                    bestMove = "Bd2",
                    userMove = "Nc3",
                    comment = "7. Bd2 защищает от связки надёжнее, не отдавая пешку e4.",
                    fenBefore = "r1bqk2r/pppp1ppp/2n2n2/8/1bBPP3/5N2/PP3PPP/RNBQK2R w KQkq - 1 7"
                )
            )

            val exerciseId = db.exerciseDao().insertExercise(
                Exercise(
                    fenPosition = "6k1/5ppp/8/8/8/8/5PPP/4R1K1 w - - 0 1",
                    solutionMoves = listOf("e1e8"),
                    tacticalPattern = TacticalPattern.BACK_RANK_MATE,
                    patternClusterId = "BACK_RANK_MATE_ONE_MOVE",
                    difficultyRating = 35,
                    patternFeatures = listOf("back_rank", "mate_in_1"),
                    theme = TacticalTheme.MATE,
                    rating = 1250,
                    createdFrom = ExerciseSource.MISTAKE,
                    sourceGameId = game1Id,
                    sourceMoveNumber = 7,
                    createdAt = now - 3 * dayMs
                )
            )
            db.exerciseDao().insertAttempt(
                ExerciseAttempt(
                    userId = userId,
                    exerciseId = exerciseId,
                    solved = false,
                    timeSpentSeconds = 8,
                    movesMade = listOf("e1c1"),
                    attemptedAt = now - dayMs
                )
            )

            val game2Id = gameDao.insert(
                Game(
                    userId = userId,
                    pgnData = """
                        [Event "Debug Sample Blitz"]
                        [Site "ChessMentor Debug"]
                        [White "DebugRival-Beta"]
                        [Black "Тестировщик"]
                        [Result "1-0"]
                        [ECO "C00"]
                        [Opening "French Defense"]
                        [TimeControl "180+2"]

                        1. e4 {[%clk 0:03:00]} e6 {[%clk 0:02:58]} 2. d4 {[%clk 0:02:55]} d5 {[%clk 0:02:51]} 3. exd5 {[%clk 0:02:49]} exd5 {[%clk 0:02:45]} 4. Nf3 {[%clk 0:02:42]} Nf6 {[%clk 0:00:14]} 5. Bd3 {[%clk 0:02:38]} Bd6 {[%clk 0:00:11]} 1-0
                    """.trimIndent(),
                    playerColor = ChessColor.BLACK,
                    timeControl = "180+2",
                    playedAt = now - dayMs,
                    analysisStatus = AnalysisStatus.COMPLETED,
                    analyzedAt = now - dayMs + 45_000L,
                    accuracy = 71.2,
                    averageEvaluationLoss = 58,
                    totalMistakes = 1,
                    blundersCount = 1,
                    mistakesCount = 0,
                    inaccuraciesCount = 0,
                    evaluationsJson = "[20,25,28,24,20,18,22,20,25,210,215]"
                )
            )
            db.analyzedMoveDao().insertAll(
                listOf(
                    AnalyzedMove(gameId = game2Id, moveIndex = 0, moveNumber = 1, color = ChessColor.WHITE, quality = MoveQuality.BOOK, san = "e4", bestMove = "e4", evalBefore = 20, evalAfter = 25, evalLoss = 0),
                    AnalyzedMove(
                        gameId = game2Id,
                        moveIndex = 9,
                        moveNumber = 5,
                        color = ChessColor.BLACK,
                        quality = MoveQuality.BLUNDER,
                        san = "Bd6",
                        bestMove = "Be7",
                        evalBefore = 25,
                        evalAfter = 210,
                        evalLoss = 185,
                        comment = "В цейтноте надёжнее спокойное 5... Be7."
                    )
                )
            )
            db.mistakeDao().insert(
                Mistake(
                    gameId = game2Id,
                    themeId = 11L,
                    moveNumber = 5,
                    color = ChessColor.BLACK,
                    evaluationLoss = 185,
                    mistakeType = MistakeType.BLUNDER,
                    bestMove = "Be7",
                    userMove = "Bd6",
                    comment = "В цейтноте надёжнее спокойное 5... Be7.",
                    fenBefore = "rnbqkb1r/ppp2ppp/5n2/3p4/3P4/3B1N2/PPP2PPP/RNBQK2R b KQkq - 3 5"
                )
            )

            gameDao.insert(
                Game(
                    userId = userId,
                    pgnData = """
                        [Event "Debug Pending Game"]
                        [Site "ChessMentor Debug"]
                        [White "Тестировщик"]
                        [Black "DebugRival-Gamma"]
                        [Result "1-0"]
                        [ECO "D30"]
                        [Opening "Queen's Gambit Declined"]
                        [TimeControl "300+3"]

                        1. d4 d5 2. c4 e6 3. Nc3 Nf6 4. Bg5 Be7 1-0
                    """.trimIndent(),
                    playerColor = ChessColor.WHITE,
                    timeControl = "300+3",
                    playedAt = now - 3_600_000L,
                    analysisStatus = AnalysisStatus.PENDING
                )
            )

            3
        }
    }
}
