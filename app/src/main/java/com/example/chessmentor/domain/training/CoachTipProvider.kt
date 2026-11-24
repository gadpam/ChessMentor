// domain/training/CoachTipProvider.kt
package com.example.chessmentor.domain.training

import com.example.chessmentor.domain.entity.AnalysisStatus
import com.example.chessmentor.domain.entity.Game
import com.example.chessmentor.domain.entity.SkillLevel
import com.example.chessmentor.domain.entity.User
import kotlin.math.abs
import kotlin.math.roundToInt

enum class CoachTipActionKind {
    NONE,
    ANALYZE_PENDING_GAME,
    REVIEW_WEAK_PATTERNS,
    PRACTICE_RECENT_GAME,
    SOLVE_DAILY_PUZZLE
}

data class CoachTip(
    val text: String,
    val actionLabel: String? = null,
    val actionKind: CoachTipActionKind = CoachTipActionKind.NONE,
    val targetGameId: Long? = null
)

/**
 * Формирует персональный совет тренера на главном экране на основе профиля игрока,
 * недавних партий, очереди интервального повторения SM-2 и статуса задачи дня.
 */
object CoachTipProvider {

    fun buildTip(
        user: User,
        games: List<Game>,
        dueReviewCount: Int,
        isDailySolved: Boolean,
        epochDay: Long
    ): CoachTip {
        val pendingGame = games.firstOrNull {
            it.id != null &&
                (it.analysisStatus == AnalysisStatus.PENDING || it.analysisStatus == AnalysisStatus.FAILED)
        }
        val latestAnalyzed = games.firstOrNull { it.isAnalyzed() && it.id != null }

        // 1. Накопленные задачи на интервальное повторение SM-2
        if (dueReviewCount >= 3) {
            return CoachTip(
                text = "К повторению готовы $dueReviewCount задач — закрепите знакомые мотивы сейчас, чтобы не терять навык.",
                actionLabel = "Повторить",
                actionKind = CoachTipActionKind.REVIEW_WEAK_PATTERNS
            )
        }

        // 2. Свежая проанализированная партия с просадкой точности или множественными ошибками
        if (latestAnalyzed != null) {
            val acc = latestAnalyzed.accuracy
            if ((acc != null && acc < 72.0) || latestAnalyzed.blundersCount >= 2 || latestAnalyzed.totalMistakes >= 4) {
                val accPart = acc?.let { "точность ${it.roundToInt()}%" } ?: "ошибок: ${latestAnalyzed.totalMistakes}"
                return CoachTip(
                    text = "В недавней партии $accPart — разберите переломные моменты, чтобы закрыть повторяющиеся зевки.",
                    actionLabel = "Открыть разбор",
                    actionKind = CoachTipActionKind.PRACTICE_RECENT_GAME,
                    targetGameId = latestAnalyzed.id
                )
            }
        }

        // 3. Есть неразобранная партия в библиотеке
        if (pendingGame != null) {
            return CoachTip(
                text = "В библиотеке ждёт неразобранная партия — Stockfish найдёт переломные моменты и создаст задачи из ваших ошибок.",
                actionLabel = "Разобрать",
                actionKind = CoachTipActionKind.ANALYZE_PENDING_GAME,
                targetGameId = pendingGame.id
            )
        }

        // 4. Задача дня ещё не решена
        if (!isDailySolved) {
            return CoachTip(
                text = "Задача дня ещё не решена — пара минут точного расчёта перед партиями разгоняет тактическое зрение.",
                actionLabel = "Решить",
                actionKind = CoachTipActionKind.SOLVE_DAILY_PUZZLE
            )
        }

        // 5. Персонализированная ротация по рейтингу пользователя и календарному дню
        val tips = when (SkillLevel.fromRating(user.rating)) {
            SkillLevel.BEGINNER -> BEGINNER_TIPS
            SkillLevel.INTERMEDIATE -> INTERMEDIATE_TIPS
            SkillLevel.ADVANCED -> ADVANCED_TIPS
        }
        val idx = (abs(epochDay + user.gamesAnalyzed) % tips.size).toInt()
        return CoachTip(text = tips[idx])
    }

    private val BEGINNER_TIPS = listOf(
        "Перед каждым ходом проверьте все шахи, взятия и нападения соперника — это убирает большинство зевков.",
        "Выводите лёгкие фигуры ближе к центру и не затягивайте с рокировкой.",
        "Не оставляйте фигуры без защиты: каждая «висячая» фигура — потенциальная мишень для двойного удара."
    )

    private val INTERMEDIATE_TIPS = listOf(
        "В миттельшпиле ищите не только свой план, но и профилактику: что соперник хочет сыграть следующим ходом?",
        "Замечайте перегруженные фигуры соперника — одна фигура редко справляется с защитой двух объектов.",
        "В выигранной позиции упрощайте игру и контролируйте встречные шахи, не торопясь забрать лишнюю пешку."
    )

    private val ADVANCED_TIPS = listOf(
        "Выбирайте из 2–3 ходов-кандидатов и сравнивайте их с точки зрения активности худшей фигуры.",
        "Перед разменом оценивайте пешечную структуру эндшпиля: иногда оставленный в центре конь сильнее слона.",
        "Когда у соперника остаётся мало времени, создавайте практические проблемы и держите напряжение в позиции."
    )
}
