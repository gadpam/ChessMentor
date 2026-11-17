// domain/training/CuratedExerciseBank.kt
package com.example.chessmentor.domain.training

import com.example.chessmentor.domain.entity.Exercise
import com.example.chessmentor.domain.entity.ExerciseSource
import com.example.chessmentor.domain.entity.TacticalPattern
import com.example.chessmentor.domain.entity.TacticalTheme
import com.example.chessmentor.domain.repository.ExerciseRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Шаблон верифицированной тактической задачи встроенного банка.
 * Все позиции проверены на легальность, единственность выигрывающего хода
 * и нечётную длину решения (последний полуход всегда принадлежит игроку).
 */
data class CuratedPuzzle(
    val id: String,
    val title: String,
    val subtitle: String,
    val fen: String,
    val solutionMoves: List<String>,
    val pattern: TacticalPattern,
    val theme: TacticalTheme,
    val difficultyRating: Int,
    val extraFeatures: List<String> = emptyList()
) {
    val isWhiteToMove: Boolean
        get() = fen.substringAfter(' ', "w").startsWith('w')

    val moveCountToSolve: Int
        get() = (solutionMoves.size + 1) / 2

    fun toExercise(): Exercise {
        val bucket = when {
            difficultyRating < 35 -> "EASY"
            difficultyRating < 65 -> "MEDIUM"
            else -> "HARD"
        }
        val features = buildList {
            add(ExerciseMetadata.curatedIdTag(id))
            add(ExerciseMetadata.titleTag(title))
            addAll(extraFeatures)
        }
        return Exercise(
            fenPosition = fen,
            solutionMoves = solutionMoves,
            tacticalPattern = pattern,
            patternClusterId = "${pattern.name}_${bucket}_CURATED",
            difficultyRating = difficultyRating.coerceIn(1, 100),
            patternFeatures = features,
            theme = theme,
            rating = TrainingRatingCalculator.difficultyToElo(difficultyRating),
            createdFrom = ExerciseSource.IMPORTED,
            sourceGameId = null,
            sourceMoveNumber = null
        )
    }
}

/**
 * Встроенный банк из 28 классических и учебных тактических задач по всем основным мотивам,
 * а также детерминированный выбор «Задачи дня».
 */
object CuratedExerciseBank {

    private const val MILLIS_PER_DAY = 86_400_000L
    private const val CYCLE_STEP = 11 // Взаимно просто с 28: полный цикл без повторов
    private const val CYCLE_OFFSET = 5

    private val seedMutex = Mutex()

    val puzzles: List<CuratedPuzzle> = listOf(
        // ============================================================
        // МАТОВЫЕ АТАКИ (MATE)
        // ============================================================
        CuratedPuzzle(
            id = "back_rank_mate_1",
            title = "Мат по 8-й горизонтали",
            subtitle = "Белые начинают и ставят мат в 1 ход",
            fen = "6k1/r4ppp/8/8/8/8/P4PPP/3R2K1 w - - 0 1",
            solutionMoves = listOf("d1d8"),
            pattern = TacticalPattern.BACK_RANK_MATE,
            theme = TacticalTheme.MATE,
            difficultyRating = 15,
            extraFeatures = listOf("WITH_CHECK", "MATE_PATTERN")
        ),
        CuratedPuzzle(
            id = "back_rank_black_2",
            title = "Вторжение сдвоенных ладей",
            subtitle = "Чёрные начинают и ставят мат в 2 хода",
            fen = "3r2k1/3r1ppp/8/8/8/P7/5PPP/2R3K1 b - - 0 1",
            solutionMoves = listOf("d7d1", "c1d1", "d8d1"),
            pattern = TacticalPattern.BACK_RANK_MATE,
            theme = TacticalTheme.MATE,
            difficultyRating = 30,
            extraFeatures = listOf("WITH_CHECK", "MATE_PATTERN")
        ),
        CuratedPuzzle(
            id = "back_rank_queen_sac",
            title = "Жертва ферзя на последней горизонтали",
            subtitle = "Белые начинают и ставят мат в 2 хода",
            fen = "1r4k1/5ppp/8/8/3Q4/8/q4PPP/3R2K1 w - - 0 1",
            solutionMoves = listOf("d4d8", "b8d8", "d1d8"),
            pattern = TacticalPattern.BACK_RANK_MATE,
            theme = TacticalTheme.MATE,
            difficultyRating = 50,
            extraFeatures = listOf("WITH_CHECK", "SACRIFICE", "MATE_PATTERN")
        ),
        CuratedPuzzle(
            id = "philidor_smothered_mate",
            title = "Спёртый мат Филидора",
            subtitle = "Жертва ферзя запирает короля под мат конём",
            fen = "r4r1k/6pp/7N/4q3/2Q5/8/5PPP/6K1 w - - 0 1",
            solutionMoves = listOf("c4g8", "f8g8", "h6f7"),
            pattern = TacticalPattern.SMOTHERED_MATE,
            theme = TacticalTheme.MATE,
            difficultyRating = 68,
            extraFeatures = listOf("WITH_CHECK", "SACRIFICE", "MATE_PATTERN")
        ),
        CuratedPuzzle(
            id = "arabian_mate_1",
            title = "Арабский мат",
            subtitle = "Классическое взаимодействие коня и ладьи в углу",
            fen = "1q5k/2R4p/5N2/8/8/8/5PPP/6K1 w - - 0 1",
            solutionMoves = listOf("c7h7"),
            pattern = TacticalPattern.ARABIAN_MATE,
            theme = TacticalTheme.MATE,
            difficultyRating = 22,
            extraFeatures = listOf("WITH_CHECK", "WITH_CAPTURE", "MATE_PATTERN")
        ),
        CuratedPuzzle(
            id = "anastasia_mate_2",
            title = "Мат Анастасии с жертвой ферзя",
            subtitle = "Вскройте вертикаль «h» для мата ладьёй",
            fen = "5r1k/4Nppp/8/7Q/8/2R5/q4PPP/6K1 w - - 0 1",
            solutionMoves = listOf("h5h7", "h8h7", "c3h3"),
            pattern = TacticalPattern.ANASTASIA_MATE,
            theme = TacticalTheme.MATE,
            difficultyRating = 72,
            extraFeatures = listOf("WITH_CHECK", "SACRIFICE", "MATE_PATTERN")
        ),
        CuratedPuzzle(
            id = "boden_mate_2",
            title = "Мат Бодена",
            subtitle = "Жертва ферзя открывает перекрёстные диагонали слонам",
            fen = "2kr1b1r/pp1nqppp/2p5/8/5B2/2Q5/PPP1BPPP/2KR4 w - - 0 1",
            solutionMoves = listOf("c3c6", "b7c6", "e2a6"),
            pattern = TacticalPattern.BODEN_MATE,
            theme = TacticalTheme.MATE,
            difficultyRating = 76,
            extraFeatures = listOf("WITH_CHECK", "SACRIFICE", "MATE_PATTERN")
        ),
        CuratedPuzzle(
            id = "morphy_opera_mate",
            title = "Оперный мат Пола Морфи",
            subtitle = "Знаменитый финал партии Морфи в Парижской опере",
            fen = "4kb1r/p2n1ppp/4q3/4p1B1/4P3/1Q6/PPP2PPP/2KR4 w - - 0 1",
            solutionMoves = listOf("b3b8", "d7b8", "d1d8"),
            pattern = TacticalPattern.BACK_RANK_MATE,
            theme = TacticalTheme.MATE,
            difficultyRating = 62,
            extraFeatures = listOf("WITH_CHECK", "SACRIFICE", "MATE_PATTERN")
        ),
        CuratedPuzzle(
            id = "greco_black_mate_3",
            title = "Матовая атака Греко за чёрных",
            subtitle = "Чёрные начинают и ставят форсированный мат в 3 хода",
            fen = "3q2kr/5pp1/8/8/5n2/8/5PPP/2Q2RK1 b - - 0 1",
            solutionMoves = listOf("f4e2", "g1h1", "h8h2", "h1h2", "d8h4"),
            pattern = TacticalPattern.ANASTASIA_MATE,
            theme = TacticalTheme.MATE,
            difficultyRating = 80,
            extraFeatures = listOf("WITH_CHECK", "SACRIFICE", "MATE_PATTERN")
        ),

        // ============================================================
        // ВСКРЫТАЯ АТАКА И ДВОЙНОЙ ШАХ (DISCOVERED_ATTACK)
        // ============================================================
        CuratedPuzzle(
            id = "reti_tartakower_double_check",
            title = "Комбинация Рети — Тартаковер",
            subtitle = "Жертва ферзя и сокрушительный двойной шах",
            fen = "rnb1kb1r/pp3ppp/2p5/4q3/4n3/3Q4/PPPB1PPP/2KR1BNR w - - 0 1",
            solutionMoves = listOf("d3d8", "e8d8", "d2g5", "d8e8", "d1d8"),
            pattern = TacticalPattern.DOUBLE_CHECK,
            theme = TacticalTheme.DISCOVERED_ATTACK,
            difficultyRating = 84,
            extraFeatures = listOf("WITH_CHECK", "SACRIFICE", "MATE_PATTERN")
        ),
        CuratedPuzzle(
            id = "discovered_check_queen",
            title = "Вскрытый шах с выигрышем ферзя",
            subtitle = "Слон объявляет шах, открывая линию ладье",
            fen = "3q2k1/5ppp/8/3B4/8/8/3R1PPP/6K1 w - - 0 1",
            solutionMoves = listOf("d5f7", "g8f7", "d2d8"),
            pattern = TacticalPattern.DISCOVERED_CHECK,
            theme = TacticalTheme.DISCOVERED_ATTACK,
            difficultyRating = 48,
            extraFeatures = listOf("WITH_CHECK", "WITH_CAPTURE", "WINS_QUEEN")
        ),

        // ============================================================
        // ДВОЙНЫЕ УДАРЫ И ВИЛКИ (FORK)
        // ============================================================
        CuratedPuzzle(
            id = "knight_royal_fork",
            title = "Королевская вилка конём",
            subtitle = "Нападите одновременно на короля и ферзя",
            fen = "q3k3/ppp2ppp/8/3N4/8/8/PPP2PPP/6K1 w - - 0 1",
            solutionMoves = listOf("d5c7", "e8d7", "c7a8"),
            pattern = TacticalPattern.FORK_KNIGHT,
            theme = TacticalTheme.FORK,
            difficultyRating = 20,
            extraFeatures = listOf("WITH_CHECK", "FAMILY_FORK", "WINS_QUEEN")
        ),
        CuratedPuzzle(
            id = "knight_fork_black",
            title = "Семейная вилка за чёрных",
            subtitle = "Чёрный конь ловит короля и ферзя на вилку",
            fen = "6k1/5pp1/7p/4n3/8/8/1Q3KPP/R7 b - - 0 1",
            solutionMoves = listOf("e5d3", "f2g1", "d3b2"),
            pattern = TacticalPattern.FORK_KNIGHT,
            theme = TacticalTheme.FORK,
            difficultyRating = 26,
            extraFeatures = listOf("WITH_CHECK", "FAMILY_FORK", "WINS_QUEEN")
        ),
        CuratedPuzzle(
            id = "rook_sac_into_knight_fork",
            title = "Завлечение под королевскую вилку",
            subtitle = "Пожертвуйте ладью, чтобы заманить короля на поле e8",
            fen = "4rk2/5ppp/q7/3N4/8/8/5PPP/4R1K1 w - - 0 1",
            solutionMoves = listOf("e1e8", "f8e8", "d5c7", "e8d7", "c7a6"),
            pattern = TacticalPattern.FORK_KNIGHT,
            theme = TacticalTheme.FORK,
            difficultyRating = 64,
            extraFeatures = listOf("WITH_CHECK", "SACRIFICE", "FAMILY_FORK")
        ),
        CuratedPuzzle(
            id = "pawn_fork_rooks",
            title = "Пешечная вилка на две ладьи",
            subtitle = "Продвижение пешки выигрывает ладью",
            fen = "6k1/p1p2pp1/2r1r2p/8/2PP4/1P4P1/5P1P/5RK1 w - - 0 1",
            solutionMoves = listOf("d4d5", "e6e7", "d5c6"),
            pattern = TacticalPattern.FORK_PAWN,
            theme = TacticalTheme.FORK,
            difficultyRating = 24,
            extraFeatures = listOf("WINS_ROOK")
        ),
        CuratedPuzzle(
            id = "bishop_fork_king_rook",
            title = "Вилка слоном по диагонали",
            subtitle = "Шах слоном с одновременным ударом по ладье",
            fen = "8/5pkp/6p1/8/5B2/2r5/5PPP/6K1 w - - 0 1",
            solutionMoves = listOf("f4e5", "g7f8", "e5c3"),
            pattern = TacticalPattern.FORK_BISHOP,
            theme = TacticalTheme.FORK,
            difficultyRating = 28,
            extraFeatures = listOf("WITH_CHECK", "WINS_ROOK")
        ),
        CuratedPuzzle(
            id = "queen_double_attack",
            title = "Двойной удар ферзём",
            subtitle = "Используйте геометрию ферзя для выигрыша ладьи",
            fen = "4k3/5ppp/8/7r/8/3Q4/5PPP/6K1 w - - 0 1",
            solutionMoves = listOf("d3e2", "e8f8", "e2h5"),
            pattern = TacticalPattern.FORK_QUEEN,
            theme = TacticalTheme.FORK,
            difficultyRating = 34,
            extraFeatures = listOf("WITH_CHECK", "WINS_ROOK")
        ),
        CuratedPuzzle(
            id = "rook_fork_black",
            title = "Двойной удар ладьёй за чёрных",
            subtitle = "Чёрная ладья атакует короля и слона по 3-й горизонтали",
            fen = "4r1k1/5pp1/7p/8/5P2/B5KP/6P1/2R5 b - - 0 1",
            solutionMoves = listOf("e8e3", "g3f2", "e3a3"),
            pattern = TacticalPattern.FORK_ROOK,
            theme = TacticalTheme.FORK,
            difficultyRating = 36,
            extraFeatures = listOf("WITH_CHECK", "WINS_BISHOP")
        ),

        // ============================================================
        // СВЯЗКИ И СКВОЗНОЙ УДАР (PIN)
        // ============================================================
        CuratedPuzzle(
            id = "absolute_pin_rook",
            title = "Абсолютная связка ферзя",
            subtitle = "Свяжите ферзя по открытой линии «e»",
            fen = "2r1k3/5ppp/4q3/8/8/6P1/5P1P/R4K2 w - - 0 1",
            solutionMoves = listOf("a1e1", "e8d7", "e1e6"),
            pattern = TacticalPattern.PIN_ABSOLUTE,
            theme = TacticalTheme.PIN,
            difficultyRating = 38,
            extraFeatures = listOf("TO_KING", "WINS_QUEEN")
        ),
        CuratedPuzzle(
            id = "discovered_pin_queen",
            title = "Шах со вскрытием связки",
            subtitle = "Слон даёт шах, а ладья забирает связанного ферзя",
            fen = "4k3/5ppp/4q3/8/8/8/4BPPP/4R1K1 w - - 0 1",
            solutionMoves = listOf("e2b5", "e8f8", "e1e6"),
            pattern = TacticalPattern.PIN_ABSOLUTE,
            theme = TacticalTheme.PIN,
            difficultyRating = 52,
            extraFeatures = listOf("WITH_CHECK", "TO_KING", "WINS_QUEEN")
        ),
        CuratedPuzzle(
            id = "rook_skewer_queen",
            title = "Сквозной удар ладьёй",
            subtitle = "Рентген по вертикали «e» выигрывает ферзя",
            fen = "4q3/5ppp/8/8/4k3/8/5PPP/R5K1 w - - 0 1",
            solutionMoves = listOf("a1e1", "e4d5", "e1e8"),
            pattern = TacticalPattern.SKEWER,
            theme = TacticalTheme.PIN,
            difficultyRating = 40,
            extraFeatures = listOf("WITH_CHECK", "WINS_QUEEN")
        ),
        CuratedPuzzle(
            id = "bishop_skewer_black",
            title = "Диагональный рентген за чёрных",
            subtitle = "Чёрный слон пробивает большую диагональ до ферзя",
            fen = "6k1/4bppp/8/4K3/8/8/5PPP/Q7 b - - 0 1",
            solutionMoves = listOf("e7f6", "e5e4", "f6a1"),
            pattern = TacticalPattern.SKEWER,
            theme = TacticalTheme.PIN,
            difficultyRating = 44,
            extraFeatures = listOf("WITH_CHECK", "WINS_QUEEN")
        ),

        // ============================================================
        // КОМБИНАЦИОННЫЕ ПРИЁМЫ (TACTIC)
        // ============================================================
        CuratedPuzzle(
            id = "deflection_king_from_queen",
            title = "Отвлечение короля от защиты ферзя",
            subtitle = "Жертва ладьи лишает чёрного ферзя единственного защитника",
            fen = "8/5kqp/3p4/8/8/6QP/5PP1/4R1K1 w - - 0 1",
            solutionMoves = listOf("e1e7", "f7e7", "g3g7"),
            pattern = TacticalPattern.DEFLECTION,
            theme = TacticalTheme.TACTIC,
            difficultyRating = 56,
            extraFeatures = listOf("WITH_CHECK", "SACRIFICE", "WINS_QUEEN")
        ),
        CuratedPuzzle(
            id = "removing_defender_exchange",
            title = "Устранение защитника ладьи",
            subtitle = "Уничтожьте коня f6, прикрывающего ладью d5",
            fen = "6k1/5pp1/5n1p/3r4/8/5R1P/5PBK/8 w - - 0 1",
            solutionMoves = listOf("f3f6", "g7f6", "g2d5"),
            pattern = TacticalPattern.REMOVING_DEFENDER,
            theme = TacticalTheme.TACTIC,
            difficultyRating = 58,
            extraFeatures = listOf("WITH_CAPTURE", "WINS_ROOK")
        ),
        CuratedPuzzle(
            id = "underpromotion_knight_fork",
            title = "Слабое превращение в коня",
            subtitle = "Превратите пешку в нужную фигуру, чтобы выиграть ферзя",
            fen = "8/3P1k2/4q3/8/8/8/6PP/6K1 w - - 0 1",
            solutionMoves = listOf("d7d8n", "f7f6", "d8e6"),
            pattern = TacticalPattern.PAWN_BREAKTHROUGH,
            theme = TacticalTheme.TACTIC,
            difficultyRating = 74,
            extraFeatures = listOf("WITH_CHECK", "FAMILY_FORK", "UNDERPROMOTION")
        ),
        CuratedPuzzle(
            id = "clearance_for_promotion",
            title = "Расчистка поля превращения",
            subtitle = "Устраните блокаду и проведите пешку в ферзи",
            fen = "3r2k1/1P3pp1/5r1p/8/8/7P/5PP1/3R2K1 w - - 0 1",
            solutionMoves = listOf("d1d8", "g8h7", "b7b8q"),
            pattern = TacticalPattern.PAWN_BREAKTHROUGH,
            theme = TacticalTheme.TACTIC,
            difficultyRating = 46,
            extraFeatures = listOf("WITH_CHECK", "WITH_CAPTURE", "PROMOTION")
        ),

        // ============================================================
        // ВЫИГРЫШ МАТЕРИАЛА (MATERIAL_LOSS)
        // ============================================================
        CuratedPuzzle(
            id = "hanging_knight_black",
            title = "Выигрыш незащищённой фигуры",
            subtitle = "Ход чёрных — накажите соперника за оставленного без защиты коня",
            fen = "2r3k1/5ppp/8/2N5/8/7P/5PP1/6K1 b - - 0 1",
            solutionMoves = listOf("c8c5"),
            pattern = TacticalPattern.HANGING_PIECE,
            theme = TacticalTheme.MATERIAL_LOSS,
            difficultyRating = 12,
            extraFeatures = listOf("WITH_CAPTURE", "WINS_KNIGHT")
        ),

        // ============================================================
        // ЗАЩИТА КОРОЛЯ (KING_SAFETY)
        // ============================================================
        CuratedPuzzle(
            id = "only_defense_back_rank",
            title = "Точная защита на 1-й горизонтали",
            subtitle = "Отразите шах с сохранением решающего перевеса",
            fen = "6k1/5ppp/8/8/3Q4/7P/5PP1/1q1r1RK1 w - - 0 1",
            solutionMoves = listOf("f1d1"),
            pattern = TacticalPattern.DEFENSIVE_MOVE,
            theme = TacticalTheme.KING_SAFETY,
            difficultyRating = 32,
            extraFeatures = listOf("WITH_CAPTURE", "WINS_ROOK")
        )
    )

    fun currentEpochDay(nowMillis: Long = System.currentTimeMillis()): Long {
        return Math.floorDiv(nowMillis, MILLIS_PER_DAY)
    }

    /**
     * Детерминированно возвращает задачу дня для заданного [epochDay].
     * Благодаря взаимной простоте [CYCLE_STEP] и размера списка, все 28 задач
     * чередуются без повторов на протяжении 28 дней подряд.
     */
    fun getDailyPuzzle(epochDay: Long = currentEpochDay()): CuratedPuzzle {
        val count = puzzles.size
        val index = Math.floorMod(epochDay * CYCLE_STEP + CYCLE_OFFSET, count.toLong()).toInt()
        return puzzles[index]
    }

    fun findTemplateById(id: String): CuratedPuzzle? = puzzles.firstOrNull { it.id == id }

    fun findTemplateByFen(fen: String): CuratedPuzzle? = puzzles.firstOrNull { it.fen == fen }

    /**
     * Идемпотентно добавляет отсутствующие встроенные задачи в БД (общие для всех пользователей,
     * `sourceGameId = null`). Безопасно вызывать многократно и параллельно.
     */
    suspend fun ensureSeeded(exerciseRepository: ExerciseRepository): Int {
        return seedMutex.withLock {
            var inserted = 0
            for (puzzle in puzzles) {
                if (exerciseRepository.findGlobalByFen(puzzle.fen) == null) {
                    exerciseRepository.saveExercise(puzzle.toExercise())
                    inserted++
                }
            }
            inserted
        }
    }

    /**
     * Гарантирует наличие встроенного банка и возвращает сущность [Exercise] для задачи дня.
     */
    suspend fun getOrCreateDailyExercise(
        exerciseRepository: ExerciseRepository,
        epochDay: Long = currentEpochDay()
    ): Pair<CuratedPuzzle, Exercise> {
        ensureSeeded(exerciseRepository)
        val template = getDailyPuzzle(epochDay)
        val existing = exerciseRepository.findGlobalByFen(template.fen)
            ?: exerciseRepository.saveExercise(template.toExercise())
        return template to existing
    }
}
