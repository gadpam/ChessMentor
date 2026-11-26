package com.example.chessmentor.domain.analysis

import com.example.chessmentor.data.engine.ChessEngine
import com.example.chessmentor.domain.entity.AnalyzedMove
import com.example.chessmentor.domain.entity.ChessColor
import com.example.chessmentor.domain.entity.Exercise
import com.example.chessmentor.domain.entity.ExerciseAttempt
import com.example.chessmentor.domain.entity.Mistake
import com.example.chessmentor.domain.entity.MistakeType
import com.example.chessmentor.domain.entity.MoveQuality
import com.example.chessmentor.domain.entity.TacticalPattern
import com.example.chessmentor.domain.entity.TacticalTheme
import com.example.chessmentor.domain.pgn.PgnParser
import com.example.chessmentor.domain.repository.ExerciseRepository
import com.example.chessmentor.domain.training.AttemptQuality
import com.example.chessmentor.domain.training.ExerciseMetadata
import com.example.chessmentor.domain.training.SpacedRepetitionScheduler
import com.example.chessmentor.domain.training.TrainingMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameReviewHelperTest {

    // ==================== НАВИГАЦИЯ И ФИЛЬТРЫ ПО КЛЮЧЕВЫМ МОМЕНТАМ ====================

    @Test
    fun filterAndNavigateKeyMoments_separatesPlayerMistakesHighlightsAndOpponentBlunders() {
        val analyzed = listOf(
            AnalyzedMove(
                gameId = 1L, moveIndex = 0, moveNumber = 1, color = ChessColor.WHITE,
                quality = MoveQuality.BOOK, san = "e4", evalBefore = 20, evalAfter = 25, evalLoss = 0
            ),
            AnalyzedMove(
                gameId = 1L, moveIndex = 4, moveNumber = 3, color = ChessColor.WHITE,
                quality = MoveQuality.INACCURACY, san = "h3", bestMove = "Bc4",
                evalBefore = 40, evalAfter = -30, evalLoss = 70
            ),
            AnalyzedMove(
                gameId = 1L, moveIndex = 7, moveNumber = 4, color = ChessColor.BLACK,
                quality = MoveQuality.BLUNDER, san = "Nxe4", bestMove = "Be7",
                evalBefore = -20, evalAfter = 320, evalLoss = 340
            ),
            AnalyzedMove(
                gameId = 1L, moveIndex = 8, moveNumber = 5, color = ChessColor.WHITE,
                quality = MoveQuality.BRILLIANT, san = "Bxf7+", bestMove = "Bxf7+",
                evalBefore = 320, evalAfter = 540, evalLoss = 0
            ),
            AnalyzedMove(
                gameId = 1L, moveIndex = 12, moveNumber = 7, color = ChessColor.WHITE,
                quality = MoveQuality.BLUNDER, san = "Qg4", bestMove = "Qe2",
                evalBefore = 480, evalAfter = -260, evalLoss = 740
            )
        )

        val moments = analyzed.map { it.toKeyMoment(ChessColor.WHITE) }

        val all = GameReviewHelper.filterKeyMoments(moments, GameReviewHelper.MomentFilter.ALL)
        assertEquals(listOf(4, 7, 8, 12), all.map { it.moveIndex })

        val myMistakes = GameReviewHelper.filterKeyMoments(
            moments,
            GameReviewHelper.MomentFilter.PLAYER_MISTAKES
        )
        assertEquals(listOf(4, 12), myMistakes.map { it.moveIndex })

        val highlights = GameReviewHelper.filterKeyMoments(
            moments,
            GameReviewHelper.MomentFilter.HIGHLIGHTS
        )
        assertEquals(listOf(8), highlights.map { it.moveIndex })

        val oppMistakes = GameReviewHelper.filterKeyMoments(
            moments,
            GameReviewHelper.MomentFilter.OPPONENT_MISTAKES
        )
        assertEquals(listOf(7), oppMistakes.map { it.moveIndex })
        // Зевок соперника даёт положительную дельту с точки зрения игрока
        assertEquals("4... Nxe4", oppMistakes.first().formattedMove())
        assertTrue(oppMistakes.first().userPerspectiveDelta() > 0)

        // Пошаговая навигация вперёд и назад по ключевым моментам
        val nav = GameReviewHelper.navigableMomentIndices(analyzed, emptyList(), ChessColor.WHITE)
        assertEquals(4, GameReviewHelper.findNextMomentIndex(-1, nav))
        assertEquals(7, GameReviewHelper.findNextMomentIndex(4, nav))
        assertEquals(12, GameReviewHelper.findNextMomentIndex(8, nav))
        assertNull(GameReviewHelper.findNextMomentIndex(12, nav))
        assertEquals(8, GameReviewHelper.findPreviousMomentIndex(12, nav))
        assertNull(GameReviewHelper.findPreviousMomentIndex(4, nav))
    }

    // ==================== ИНТЕРАКТИВНЫЙ РЕЖИМ «ИСПРАВИТЬ ОШИБКУ» ====================

    @Test
    fun evaluateRetryAttemptAndHints_handlesSanUciPromotionsAndGameMistake() {
        val scholarFen = "r1bqkb1r/pppp1ppp/2n2n2/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR w KQkq - 4 4"

        // Повторение ошибочного хода из партии
        val repeated = GameReviewHelper.evaluateRetryAttempt(
            fenBefore = scholarFen,
            attemptedNotation = "d2d3",
            expectedBestNotation = "Qxf7#",
            playedMistakeNotation = "d3"
        )
        assertEquals(GameReviewHelper.RetryVerdict.SAME_AS_GAME_MISTAKE, repeated.verdict)
        assertEquals("Qxf7#", repeated.expectedBestSan)

        // Другой легальный, но не лучший ход
        val alt = GameReviewHelper.evaluateRetryAttempt(
            fenBefore = scholarFen,
            attemptedNotation = "g1f3",
            expectedBestNotation = "Qxf7#",
            playedMistakeNotation = "d3"
        )
        assertEquals(GameReviewHelper.RetryVerdict.ALTERNATIVE_LEGAL, alt.verdict)

        // Точное нахождение лучшего хода (подано в UCI, эталон — в SAN)
        val exact = GameReviewHelper.evaluateRetryAttempt(
            fenBefore = scholarFen,
            attemptedNotation = "h5f7",
            expectedBestNotation = "Qxf7#",
            playedMistakeNotation = "d3"
        )
        assertEquals(GameReviewHelper.RetryVerdict.EXACT_BEST, exact.verdict)
        assertEquals("Qxf7#", exact.attemptedSan)

        // Двухступенчатые подсказки (сначала только поле фигуры, затем полная стрелка)
        val hint1 = GameReviewHelper.buildRetryHint(scholarFen, "Qxf7#", level = 1)
        assertNotNull(hint1)
        assertEquals("h5", hint1!!.fromSquare)
        assertNull(hint1.toSquare)

        val hint2 = GameReviewHelper.buildRetryHint(scholarFen, "Qxf7#", level = 2)
        assertNotNull(hint2)
        assertEquals("h5", hint2!!.fromSquare)
        assertEquals("f7", hint2.toSquare)
        assertEquals("h5f7", hint2.bestUci)

        // Проверка превращения пешки в коня (недопревращение: e8=N vs e8=Q)
        val promoFen = "7k/4P3/8/8/8/8/8/4K3 w - - 0 1"
        val wrongPromo = GameReviewHelper.evaluateRetryAttempt(
            fenBefore = promoFen,
            attemptedNotation = "e7e8q",
            expectedBestNotation = "e8=N",
            playedMistakeNotation = "Kf2"
        )
        assertEquals(GameReviewHelper.RetryVerdict.ALTERNATIVE_LEGAL, wrongPromo.verdict)

        val rightPromo = GameReviewHelper.evaluateRetryAttempt(
            fenBefore = promoFen,
            attemptedNotation = "e7e8n",
            expectedBestNotation = "e8=N",
            playedMistakeNotation = "Kf2"
        )
        assertEquals(GameReviewHelper.RetryVerdict.EXACT_BEST, rightPromo.verdict)
    }

    // ==================== ЛИНИИ STOCKFISH (PV), ЧАСЫ И МАТЕРИАЛ ====================

    @Test
    fun buildVariationStepsClocksAndMaterial_formatBlackStartAndTimeTroubleAccurately() {
        val blackStartFen = "r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 3 3"
        val steps = GameReviewHelper.buildVariationSteps(
            startFen = blackStartFen,
            moves = listOf("g8f6", "f3g5", "d7d5", "e4d5"),
            maxPlies = 6
        )
        assertEquals(4, steps.size)
        assertEquals("3... Nf6 4. Ng5 d5 5. exd5", GameReviewHelper.formatVariationLine(steps))
        // После 5. exd5 у белых лишняя пешка (+1)
        assertEquals(1, GameReviewHelper.materialDiffWhitePawns(steps.last().fenAfter))

        // Перевод UCI-оценки со стороны ходящего в оценку со стороны белых
        assertEquals(-180, GameReviewHelper.sideToMoveScoreToWhite(180, blackStartFen))
        assertEquals(180, GameReviewHelper.sideToMoveScoreToWhite(180, steps[0].fenAfter))

        // Извлечение и форматирование часов по полуходам
        val pgnWithClocks = """
            [White "A"]
            [Black "B"]
            1. e4 { [%clk 0:03:00] } e5 { [%clk 0:02:55] }
            2. Nf3 { [%clk 0:00:14] } Nc6 { [%clk 0:01:05:09] } *
        """.trimIndent()
        val clocks = PgnParser.extractClocksByPly(pgnWithClocks)
        assertEquals(180, GameReviewHelper.clockForSideAtPly(clocks, ChessColor.WHITE, -1))
        assertEquals(175, GameReviewHelper.clockForSideAtPly(clocks, ChessColor.BLACK, 1))
        assertEquals(14, GameReviewHelper.clockForSideAtPly(clocks, ChessColor.WHITE, 3))
        assertTrue(GameReviewHelper.isTimeTrouble(14))
        assertFalse(GameReviewHelper.isTimeTrouble(25))
        assertEquals("0:14", GameReviewHelper.formatClock(14))
        assertEquals("1:05:09", GameReviewHelper.formatClock(3909))
    }

    // ==================== ТРЕНЕРСКОЕ РЕЗЮМЕ И МАРШРУТЫ МОСТА «ПАРТИЯ → ТРЕНИРОВКА» ====================

    @Test
    fun buildCoachReport_detectsTurningPointTimeTroubleMissedBlunderAndWeakPhase() {
        val analyzed = listOf(
            // Ход 12 чёрных — грубый зевок соперника
            AnalyzedMove(
                gameId = 7L, moveIndex = 23, moveNumber = 12, color = ChessColor.BLACK,
                quality = MoveQuality.BLUNDER, san = "Bg4", bestMove = "Be7",
                evalBefore = 20, evalAfter = 340, evalLoss = 320
            ),
            // Ход 13 белых (игрок) — упущенный выигрыш в цейтноте
            AnalyzedMove(
                gameId = 7L, moveIndex = 24, moveNumber = 13, color = ChessColor.WHITE,
                quality = MoveQuality.MISSED_WIN, san = "h3", bestMove = "Nxe5",
                evalBefore = 340, evalAfter = 30, evalLoss = 310
            ),
            // Ход 22 белых (игрок) — решающий зевок в цейтноте (переломный момент)
            AnalyzedMove(
                gameId = 7L, moveIndex = 42, moveNumber = 22, color = ChessColor.WHITE,
                quality = MoveQuality.BLUNDER, san = "Rxd5", bestMove = "Rfe1",
                evalBefore = 90, evalAfter = -480, evalLoss = 570
            )
        )
        val mistakes = listOf(
            Mistake(
                id = 1L, gameId = 7L, themeId = 1L, moveNumber = 13, color = ChessColor.WHITE,
                evaluationLoss = 310, mistakeType = MistakeType.MISTAKE,
                bestMove = "Nxe5", userMove = "h3",
                fenBefore = "rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 0 2"
            ),
            Mistake(
                id = 2L, gameId = 7L, themeId = 1L, moveNumber = 22, color = ChessColor.WHITE,
                evaluationLoss = 570, mistakeType = MistakeType.BLUNDER,
                bestMove = "Rfe1", userMove = "Rxd5",
                fenBefore = "r1bqk2r/pppp1ppp/2n2n2/2b1p3/2B1P3/5N2/PPPP1PPP/RNBQ1RK1 w kq - 4 5"
            )
        )
        val phases = GameMoveClassifier.PhaseAccuracyBreakdown(
            opening = 91.0,
            middlegame = 62.0,
            endgame = null,
            openingMoves = 8,
            middlegameMoves = 12,
            endgameMoves = 0
        )
        val clocks = mapOf(24 to 16, 42 to 9)

        val report = GameReviewHelper.buildCoachReport(
            playerColor = ChessColor.WHITE,
            analyzedMoves = analyzed,
            mistakes = mistakes,
            phaseAccuracy = phases,
            clocksByPly = clocks,
            outcome = PgnParser.PlayerOutcome.LOSS
        )

        assertNotNull(report)
        assertEquals(22, report!!.turningPoint?.moveNumber)
        assertEquals("22. Rxd5", report.turningPoint?.formattedMove)
        assertEquals("Rfe1", report.turningPoint?.bestMoveSan)
        assertTrue(report.turningPoint?.inTimeTrouble == true)
        assertTrue((report.turningPoint?.winPercentDrop ?: 0) >= 40)
        assertEquals(2, report.timeTroubleMistakes)
        assertEquals(1, report.missedOpponentBlunders)
        assertEquals(2, report.trainableMistakesCount)
        assertEquals("Миттельшпиль (62%)", report.weakestPhaseLabel)
        assertEquals("Дебют (91%)", report.strongestPhaseLabel)
        assertTrue(report.bullets.size >= 3)

        // Маршрутизация TrainingMode.FromGame (вся партия и конкретный ход)
        val wholeGameMode = TrainingMode.FromGame(gameId = 7L)
        assertEquals(wholeGameMode, TrainingMode.fromRouteKey(wholeGameMode.routeKey))

        val singleMoveMode = TrainingMode.FromGame(gameId = 7L, focusMoveNumber = 22)
        assertEquals("game_7_move_22", singleMoveMode.routeKey)
        assertEquals(singleMoveMode, TrainingMode.fromRouteKey("game_7_move_22"))
        assertEquals(TrainingMode.All, TrainingMode.fromRouteKey("game_0_move_5"))
    }

    @Test
    fun parseMoveComment_separatesReasonBetterLineAndRefutation_andCoachReportUsesReasonWithoutSpoiler() {
        val structured = GameReviewHelper.parseMoveComment(
            "Зевок вилки: теряется ладья. Сильнее: 14. Nxd5 exd5. Опровержение: 14... Nc2+ 15. Kd1 Nxa1."
        )
        assertTrue(structured.isStructured)
        assertEquals("Зевок вилки: теряется ладья.", structured.reason)
        assertEquals("14. Nxd5 exd5.", structured.betterLine)
        assertEquals("14... Nc2+ 15. Kd1 Nxa1.", structured.refutationLine)

        val unstructured = GameReviewHelper.parseMoveComment("Сыграйте Nf3 вместо хода в партии")
        assertFalse(unstructured.isStructured)
        assertEquals("Сыграйте Nf3 вместо хода в партии", unstructured.reason)
        assertNull(unstructured.betterLine)
        assertNull(unstructured.refutationLine)

        val analyzed = listOf(
            AnalyzedMove(
                gameId = 9L, moveIndex = 26, moveNumber = 14, color = ChessColor.WHITE,
                quality = MoveQuality.BLUNDER, san = "Qd2", bestMove = "Nxd5",
                evalBefore = 60, evalAfter = -420, evalLoss = 480
            )
        )
        val mistakes = listOf(
            Mistake(
                id = 10L, gameId = 9L, themeId = 1L, moveNumber = 14, color = ChessColor.WHITE,
                evaluationLoss = 480, mistakeType = MistakeType.BLUNDER,
                bestMove = "Nxd5", userMove = "Qd2",
                fenBefore = "r1bqk2r/pppp1ppp/2n2n2/2b1p3/2B1P3/5N2/PPPP1PPP/RNBQ1RK1 w kq - 4 5",
                comment = "Зевок вилки: теряется ладья. Сильнее: 14. Nxd5. Опровержение: 14... Nc2+."
            )
        )
        val report = GameReviewHelper.buildCoachReport(
            playerColor = ChessColor.WHITE,
            analyzedMoves = analyzed,
            mistakes = mistakes
        )
        assertNotNull(report)
        val tpBullet = report!!.bullets.first()
        assertTrue(tpBullet.contains("Зевок вилки: теряется ладья"))
        assertFalse("Summary bullet should not spoil bestMoveSan directly", tpBullet.contains("Nxd5"))
        assertEquals(
            com.example.chessmentor.domain.training.PuzzleQualityEvaluator.selectTrainableMistakes(
                mistakes = mistakes,
                analyzedByMoveAndColor = analyzed.associateBy { it.moveNumber to it.color },
                playerColor = ChessColor.WHITE
            ).size,
            report.trainableMistakesCount
        )
    }

    @Test
    fun saveRetryPositionToRepetition_savesExactPositionAndMarksAssistedCorrections() = runBlocking {
        val repo = InMemoryExerciseRepo()
        val fenBefore = "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2"

        // 1. Самостоятельное исправление (hintLevel = 0, answerRevealed = false, 1-я попытка)
        val clean = GameReviewHelper.saveRetryPositionToRepetition(
            userId = 1L,
            gameId = 15L,
            moveNumber = 2,
            fenBefore = fenBefore,
            expectedBestRaw = "Nf3",
            hintLevel = 0,
            answerRevealedBeforeRetry = false,
            attemptsCount = 1,
            isPlayerMistake = true,
            analyzedMove = null,
            mistake = null,
            exerciseRepository = repo,
            nowMs = 1_700_000_000_000L
        )
        assertNotNull(clean)
        assertTrue(clean!!.createdNew)
        assertFalse(clean.recordedAssistedAttempt)
        assertEquals(fenBefore, clean.exercise.fenPosition)
        assertEquals(listOf("g1f3"), clean.exercise.solutionMoves)
        assertTrue(repo.attempts.isEmpty())

        // Повторное сохранение той же позиции не создаёт дубликат, а при раскрытом ответе
        // записывает попытку с @REVEALED -> AttemptQuality.FAILED (коробка 0), не выдавая за самостоятельное решение
        val assisted = GameReviewHelper.saveRetryPositionToRepetition(
            userId = 1L,
            gameId = 15L,
            moveNumber = 2,
            fenBefore = fenBefore,
            expectedBestRaw = "Nf3",
            hintLevel = 2,
            answerRevealedBeforeRetry = true,
            attemptsCount = 2,
            isPlayerMistake = true,
            analyzedMove = null,
            mistake = null,
            exerciseRepository = repo,
            nowMs = 1_700_000_005_000L
        )
        assertNotNull(assisted)
        assertFalse(assisted!!.createdNew)
        assertTrue(assisted.recordedAssistedAttempt)
        assertEquals(1, repo.stored.size)
        assertEquals(1, repo.attempts.size)
        assertFalse(repo.attempts.single().solved)

        val schedule = SpacedRepetitionScheduler.buildSchedule(
            attempts = repo.attempts,
            exercisesById = mapOf(assisted.exercise.id!! to assisted.exercise)
        )
        val card = schedule[assisted.exercise.id!!]
        assertNotNull(card)
        assertEquals(AttemptQuality.FAILED, card!!.lastQuality)
        assertEquals(0, card.cleanFirstTrySolves)
        assertEquals(0, card.box)
    }

    @Test
    fun extractFirstMoveAndResolveRetrySetup_punishesOpponentBlunderAfterHisMove_andRequiresContinuation() {
        assertEquals("Kxh7", GameReviewHelper.extractFirstMoveFromLine("14... Kxh7 15. Qd3+"))
        assertEquals("Nxd5", GameReviewHelper.extractFirstMoveFromLine("15. Nxd5."))
        assertEquals("Qxh2#", GameReviewHelper.extractFirstMoveFromLine("14...Qxh2#"))
        assertNull(GameReviewHelper.extractFirstMoveFromLine("..."))
        assertNull(GameReviewHelper.extractFirstMoveFromLine(""))

        // Позиции до и после 3. Bxf7+?? (ход 5, index = 4): белые отдают слона с шахом
        val fenBeforeOpponentBlunder =
            "rnbqkb1r/pppp1ppp/5n2/4p3/2B1P3/8/PPPP1PPP/RNBQK1NR w KQkq - 2 3"
        val fenAfterOpponentBlunder =
            "rnbqkb1r/pppp1Bpp/5n2/4p3/4P3/8/PPPP1PPP/RNBQK1NR b KQkq - 0 3"

        val opponentBlunder = AnalyzedMove(
            gameId = 1L,
            moveIndex = 4,
            moveNumber = 3,
            color = ChessColor.WHITE,
            quality = MoveQuality.BLUNDER,
            san = "Bxf7+",
            bestMove = "Nf3",
            evalBefore = 15,
            evalAfter = -340,
            evalLoss = 355,
            comment = "Зевок фигуры под удар короля. Сильнее: 3. Nf3 Nc6. Опровержение: 3... Kxf7 4. Nf3."
        )
        val playerMissedNext = AnalyzedMove(
            gameId = 1L,
            moveIndex = 5,
            moveNumber = 3,
            color = ChessColor.BLACK,
            quality = MoveQuality.BLUNDER,
            san = "Ke7",
            bestMove = "Kxf7",
            evalBefore = -340,
            evalAfter = 20,
            evalLoss = 360,
            comment = "Упущен шанс наказать ошибку соперника! Сильнее: 3... Kxf7."
        )

        val setup = GameReviewHelper.resolveRetryChallengeSetup(
            targetMoveIndex = 4,
            playerColor = ChessColor.BLACK,
            fenBeforeMove = fenBeforeOpponentBlunder,
            fenAfterMove = fenAfterOpponentBlunder,
            playedSan = "Bxf7+",
            analyzedMoves = listOf(opponentBlunder, playerMissedNext),
            mistakes = emptyList(),
            sanMoves = listOf("e4", "e5", "Bc4", "Nf6", "Bxf7+", "Ke7")
        )
        assertNotNull(setup)
        assertFalse(setup!!.isPlayerMistake)
        assertEquals(ChessColor.WHITE, setup.moverColor)
        // Стартовая позиция для игрока — строго ПОСЛЕ 3. Bxf7+ (чёрный король уже под шахом)
        assertEquals(fenAfterOpponentBlunder, setup.fenToPlay)
        assertEquals("e8f7", setup.expectedBestUci)
        assertEquals("Kxf7", setup.expectedBestSan)
        assertEquals("Ke7", setup.playedMistakeNotation)

        // Ход 3... Ke7 из партии фиксируется как промах, а 3... Kxf7 засчитывается
        val missedEval = GameReviewHelper.evaluateRetryAttempt(
            fenBefore = setup.fenToPlay,
            attemptedNotation = "e8e7",
            expectedBestNotation = setup.expectedBestUci,
            playedMistakeNotation = setup.playedMistakeNotation
        )
        assertEquals(GameReviewHelper.RetryVerdict.SAME_AS_GAME_MISTAKE, missedEval.verdict)

        val winningEval = GameReviewHelper.evaluateRetryAttempt(
            fenBefore = setup.fenToPlay,
            attemptedNotation = "e8f7",
            expectedBestNotation = setup.expectedBestUci,
            playedMistakeNotation = setup.playedMistakeNotation
        )
        assertEquals(GameReviewHelper.RetryVerdict.EXACT_BEST, winningEval.verdict)

        // Запасной путь без «Опровержение:» в комментарии — из следующего полухода игрока
        val noCommentBlunder = opponentBlunder.copy(comment = "Грубый зевок! (-3.5). Сильнее: 3. Nf3.")
        val fallbackSetup = GameReviewHelper.resolveRetryChallengeSetup(
            targetMoveIndex = 4,
            playerColor = ChessColor.BLACK,
            fenBeforeMove = fenBeforeOpponentBlunder,
            fenAfterMove = fenAfterOpponentBlunder,
            playedSan = "Bxf7+",
            analyzedMoves = listOf(noCommentBlunder, playerMissedNext),
            mistakes = emptyList()
        )
        assertNotNull(fallbackSetup)
        assertEquals("e8f7", fallbackSetup!!.expectedBestUci)

        // Если нет ни «Опровержение:», ни проверенного продолжения на следующем полуходе — задача недоступна
        val noContinuation = GameReviewHelper.resolveRetryChallengeSetup(
            targetMoveIndex = 4,
            playerColor = ChessColor.BLACK,
            fenBeforeMove = fenBeforeOpponentBlunder,
            fenAfterMove = fenAfterOpponentBlunder,
            playedSan = "Bxf7+",
            analyzedMoves = listOf(noCommentBlunder),
            mistakes = emptyList()
        )
        assertNull(noContinuation)
    }

    @Test
    fun saveRetryPositionToRepetition_forOpponentBlunder_leavesSourceMoveNumberNullToAvoidCollision() =
        runBlocking {
            val repo = InMemoryExerciseRepo()
            val fenAfterOpponentBlunder =
                "rnbqkb1r/pppp1Bpp/5n2/4p3/4P3/8/PPPP1PPP/RNBQK1NR b KQkq - 0 3"

            val saved = GameReviewHelper.saveRetryPositionToRepetition(
                userId = 1L,
                gameId = 25L,
                moveNumber = 3,
                fenBefore = fenAfterOpponentBlunder,
                expectedBestRaw = "e8f7",
                hintLevel = 0,
                answerRevealedBeforeRetry = false,
                attemptsCount = 1,
                isPlayerMistake = false,
                analyzedMove = null,
                mistake = null,
                exerciseRepository = repo,
                nowMs = 1_700_000_000_000L
            )
            assertNotNull(saved)
            assertTrue(saved!!.createdNew)
            assertEquals(25L, saved.exercise.sourceGameId)
            assertNull(saved.exercise.sourceMoveNumber)
            assertNull(repo.findBySourceGameAndMove(25L, 3))
        }

    @Test
    fun resolveRetrySkillProfile_separatesAttackingDefensiveAndOpponentRefutation_andTagsSavedDefense() =
        runBlocking {
            val attackForkMistake = Mistake(
                id = 1L, gameId = 5L, themeId = 1L, moveNumber = 12, color = ChessColor.WHITE,
                evaluationLoss = 320, mistakeType = MistakeType.BLUNDER,
                bestMove = "Nd5", userMove = "a3",
                fenBefore = "r1bqk2r/pppp1ppp/2n2n2/2b1p3/2B1P3/5N2/PPPP1PPP/RNBQ1RK1 w kq - 4 5"
            )
            val attackMove = AnalyzedMove(
                gameId = 5L, moveIndex = 22, moveNumber = 12, color = ChessColor.WHITE,
                quality = MoveQuality.BLUNDER, san = "a3", bestMove = "Nd5",
                evalBefore = 190, evalAfter = -130, evalLoss = 320
            )
            val attackProfile = GameReviewHelper.resolveRetrySkillProfile(
                mistake = attackForkMistake,
                analyzedMove = attackMove,
                moveNumber = 12,
                isPlayerMistake = true
            )
            assertFalse(attackProfile.isDefensive)
            assertEquals(TacticalPattern.FORK_KNIGHT, attackProfile.pattern)
            assertEquals(TacticalTheme.FORK, attackProfile.theme)

            val defenseMistake = Mistake(
                id = 2L, gameId = 5L, themeId = 16L, moveNumber = 18, color = ChessColor.BLACK,
                evaluationLoss = 340, mistakeType = MistakeType.BLUNDER,
                bestMove = "Kg7", userMove = "h6",
                fenBefore = "6k1/5p1p/6p1/8/8/8/5PPP/3R2K1 b - - 0 18"
            )
            val defenseMove = AnalyzedMove(
                gameId = 5L, moveIndex = 35, moveNumber = 18, color = ChessColor.BLACK,
                quality = MoveQuality.BLUNDER, san = "h6", bestMove = "Kg7",
                // Для чёрных evalBefore = +80 означает -80 с их стороны (оборона)
                evalBefore = 80, evalAfter = 420, evalLoss = 340
            )
            val defenseProfile = GameReviewHelper.resolveRetrySkillProfile(
                mistake = defenseMistake,
                analyzedMove = defenseMove,
                moveNumber = 18,
                isPlayerMistake = true
            )
            assertTrue(defenseProfile.isDefensive)
            assertEquals(TacticalPattern.DEFENSIVE_MOVE, defenseProfile.pattern)
            assertEquals(TacticalTheme.KING_SAFETY, defenseProfile.theme)

            // Ответ на ошибку соперника в проигранной позиции сохраняет защитную роль,
            // а в равной/выигранной — атакующую
            val opponentSaving = GameReviewHelper.resolveRetrySkillProfile(
                mistake = null,
                analyzedMove = null,
                moveNumber = 20,
                isPlayerMistake = false,
                evalWhiteAfterOpponentMistake = -260,
                playerColor = ChessColor.WHITE
            )
            assertTrue(opponentSaving.isDefensive)
            assertEquals(TacticalTheme.KING_SAFETY, opponentSaving.theme)

            val opponentPunish = GameReviewHelper.resolveRetrySkillProfile(
                mistake = null,
                analyzedMove = null,
                moveNumber = 20,
                isPlayerMistake = false,
                evalWhiteAfterOpponentMistake = 260,
                playerColor = ChessColor.WHITE
            )
            assertFalse(opponentPunish.isDefensive)

            // Сохранение оборонительной позиции добавляет тег GOAL:DEFENSE
            val repo = InMemoryExerciseRepo()
            val savedDefense = GameReviewHelper.saveRetryPositionToRepetition(
                userId = 1L,
                gameId = 5L,
                moveNumber = 18,
                fenBefore = "6k1/5p1p/6p1/8/8/8/5PPP/3R2K1 b - - 0 18",
                expectedBestRaw = "g8g7",
                hintLevel = 0,
                answerRevealedBeforeRetry = false,
                attemptsCount = 1,
                isPlayerMistake = true,
                analyzedMove = defenseMove,
                mistake = defenseMistake,
                exerciseRepository = repo,
                nowMs = 1_700_000_000_000L
            )
            assertNotNull(savedDefense)
            assertTrue(ExerciseMetadata.isDefensiveGoal(savedDefense!!.exercise))
        }

    @Test
    fun areEngineScoresEquivalent_comparesMateTempoAndCentipawnsSeparately() {
        val winMate1 = ChessEngine.MATE_VALUE - 100
        val winMate2 = ChessEngine.MATE_VALUE - 200
        val winMate4 = ChessEngine.MATE_VALUE - 400
        val loseMate2 = -(ChessEngine.MATE_VALUE - 200)
        val loseMate4 = -(ChessEngine.MATE_VALUE - 400)

        assertTrue(GameReviewHelper.areEngineScoresEquivalent(winMate2, winMate2))
        assertTrue(GameReviewHelper.areEngineScoresEquivalent(winMate2, winMate1))
        assertFalse(GameReviewHelper.areEngineScoresEquivalent(winMate2, winMate4))
        assertFalse(GameReviewHelper.areEngineScoresEquivalent(winMate2, 950))
        assertTrue(GameReviewHelper.areEngineScoresEquivalent(800, winMate4))

        assertFalse(GameReviewHelper.areEngineScoresEquivalent(-120, loseMate4))
        assertFalse(GameReviewHelper.areEngineScoresEquivalent(loseMate4, loseMate2))
        assertTrue(GameReviewHelper.areEngineScoresEquivalent(loseMate2, loseMate4))

        assertTrue(GameReviewHelper.areEngineScoresEquivalent(100, 80))
        assertFalse(GameReviewHelper.areEngineScoresEquivalent(100, 60))
    }

    @Test
    fun resolveRetrySkillProfile_infersSemanticMotifFromFenWhenMistakeMetadataAbsent() {
        val forkProfile = GameReviewHelper.resolveRetrySkillProfile(
            mistake = null,
            analyzedMove = null,
            moveNumber = 18,
            isPlayerMistake = false,
            fenToPlay = "q3k3/ppp2ppp/8/3N4/8/8/PPP2PPP/6K1 w - - 0 1",
            expectedBestUci = "d5c7"
        )
        assertEquals(TacticalPattern.FORK_KNIGHT, forkProfile.pattern)
        assertEquals(TacticalTheme.FORK, forkProfile.theme)
        assertFalse(forkProfile.isDefensive)

        val pinProfile = GameReviewHelper.resolveRetrySkillProfile(
            mistake = null,
            analyzedMove = null,
            moveNumber = 22,
            isPlayerMistake = true,
            fenToPlay = "4k3/4b3/8/8/8/8/8/3R2K1 w - - 0 1",
            expectedBestUci = "d1e1"
        )
        assertEquals(TacticalPattern.PIN_ABSOLUTE, pinProfile.pattern)
        assertEquals(TacticalTheme.PIN, pinProfile.theme)

        val doubleCheckProfile = GameReviewHelper.resolveRetrySkillProfile(
            mistake = null,
            analyzedMove = null,
            moveNumber = 25,
            isPlayerMistake = true,
            fenToPlay = "4k3/8/4N3/8/8/8/8/4R1K1 w - - 0 1",
            expectedBestUci = "e6c7"
        )
        assertEquals(TacticalPattern.DOUBLE_CHECK, doubleCheckProfile.pattern)
        assertEquals(TacticalTheme.DISCOVERED_ATTACK, doubleCheckProfile.theme)

        val losingMove = AnalyzedMove(
            gameId = 1L, moveIndex = 40, moveNumber = 21, color = ChessColor.WHITE,
            quality = MoveQuality.BLUNDER, san = "Qb1", bestMove = "Qc7",
            evalBefore = -500, evalAfter = -900, evalLoss = 400
        )
        val stalemateProfile = GameReviewHelper.resolveRetrySkillProfile(
            mistake = null,
            analyzedMove = losingMove,
            moveNumber = 21,
            isPlayerMistake = true,
            fenToPlay = "k7/8/1K6/8/8/8/2Q5/8 w - - 0 1",
            expectedBestUci = "c2c7"
        )
        assertTrue(stalemateProfile.isDefensive)
        assertEquals(TacticalPattern.STALEMATE_TRICK, stalemateProfile.pattern)
        assertEquals(TacticalTheme.KING_SAFETY, stalemateProfile.theme)
    }

    private class InMemoryExerciseRepo : ExerciseRepository {
        val stored = mutableListOf<Exercise>()
        val attempts = mutableListOf<ExerciseAttempt>()
        private var nextId = 1L

        override suspend fun findById(id: Long): Exercise? = stored.firstOrNull { it.id == id }
        override suspend fun findByIds(ids: List<Long>): List<Exercise> = stored.filter { it.id in ids }
        override suspend fun findSuitableExercise(userId: Long, userRating: Int, excludeId: Long): Exercise? = null
        override suspend fun findFailedUnresolvedExercise(userId: Long, excludeId: Long): Exercise? = null
        override suspend fun findUserMistakeExercise(userId: Long, excludeId: Long): Exercise? = null
        override suspend fun findExerciseByTheme(userId: Long, theme: TacticalTheme, userRating: Int, excludeId: Long): Exercise? = null
        override suspend fun findByTheme(theme: String): List<Exercise> = emptyList()
        override suspend fun findByPattern(pattern: String): List<Exercise> = emptyList()
        override suspend fun findBySourceGameAndMove(gameId: Long, moveNumber: Int): Exercise? =
            stored.firstOrNull { it.sourceGameId == gameId && it.sourceMoveNumber == moveNumber }
        override suspend fun findByUserId(userId: Long): List<Exercise> = stored.toList()
        override suspend fun findAccessibleByUserId(userId: Long): List<Exercise> = stored.toList()
        override suspend fun findAnyAccessibleExcept(userId: Long, excludeId: Long): Exercise? = null
        override suspend fun findGlobalByFen(fen: String): Exercise? = null
        override suspend fun findUserExerciseByFen(userId: Long, fen: String): Exercise? =
            stored.firstOrNull { it.fenPosition == fen }
        override suspend fun findByFen(fen: String): Exercise? = stored.firstOrNull { it.fenPosition == fen }
        override suspend fun deleteDuplicatesForUser(userId: Long) = Unit
        override suspend fun deleteGlobalDuplicates() = Unit
        override suspend fun countExercises(): Int = stored.size
        override suspend fun countGlobalExercises(): Int = 0
        override suspend fun hasUserSolvedExercise(userId: Long, exerciseId: Long): Boolean = false
        override suspend fun saveExercise(exercise: Exercise): Exercise {
            val saved = if (exercise.id == null) exercise.copy(id = nextId++) else exercise
            stored.removeAll { it.id == saved.id }
            stored.add(saved)
            return saved
        }
        override suspend fun saveAttempt(attempt: ExerciseAttempt): ExerciseAttempt {
            attempts.add(attempt)
            return attempt
        }
        override suspend fun getAttemptsByUserId(userId: Long): List<ExerciseAttempt> = attempts.toList()
        override suspend fun findByCluster(userId: Long, clusterId: String, excludeId: Long, limit: Int): List<Exercise> = emptyList()
        override suspend fun findSimilarByPattern(userId: Long, pattern: String, excludeId: Long, minDifficulty: Int, maxDifficulty: Int, limit: Int): List<Exercise> = emptyList()
        override suspend fun findForMistakeRepetition(userId: Long, pattern: String, difficulty: Int, excludeId: Long, limit: Int): List<Exercise> = emptyList()
        override suspend fun incrementTimesShown(exerciseId: Long) = Unit
        override suspend fun incrementTimesSolved(exerciseId: Long) = Unit
    }
}
