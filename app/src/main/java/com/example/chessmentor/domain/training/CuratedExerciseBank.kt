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
    val preFen: String? = null,
    val prevMoveUci: String? = null,
    val explanation: String? = null,
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
            if (!prevMoveUci.isNullOrBlank()) {
                add(ExerciseMetadata.prevMoveTag(prevMoveUci))
            }
            if (!preFen.isNullOrBlank() && !prevMoveUci.isNullOrBlank()) {
                add(ExerciseMetadata.preFenTag(preFen))
            }
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
            preFen = "r5k1/5ppp/8/8/8/8/P4PPP/3R2K1 b - - 0 1",
            prevMoveUci = "a8a7",
            explanation = "Ладья чёрных покинула 8-ю горизонталь, и у короля g8 нет форточки — 1. Rd8# решает мгновенно.",
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
            preFen = "3r2k1/3r1ppp/8/8/8/8/P4PPP/2R3K1 w - - 0 1",
            prevMoveUci = "a2a3",
            explanation = "Сдвоенные ладьи по открытой линии d перегружают единственного защитника: 1... Rd1+ 2. Rxd1 Rxd1#.",
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
            preFen = "1r4k1/5ppp/8/8/3Q4/q7/P4PPP/3R2K1 b - - 0 1",
            prevMoveUci = "a3a2",
            explanation = "Жертва ферзя 1. Qd8+! отвлекает единственного защитника 8-й горизонтали: 1... Rxd8 2. Rxd8#.",
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
            preFen = "r4r1k/4q1pp/7N/8/2Q5/8/5PPP/6K1 b - - 0 1",
            prevMoveUci = "e7e5",
            explanation = "Наследие Филидора: жертва 1. Qg8+! вынуждает 1... Rxg8 (король не бьёт из-за коня h6), запирая короля под 2. Nf7#.",
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
            preFen = "3q3k/2R4p/5N2/8/8/8/5PPP/6K1 b - - 0 1",
            prevMoveUci = "d8b8",
            explanation = "Арабский мат: конь f6 одновременно защищает ладью на h7 и отнимает поле бегства g8 — 1. Rxh7#.",
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
            preFen = "5r1k/4Nppp/8/7Q/8/q1R5/P4PPP/6K1 b - - 0 1",
            prevMoveUci = "a3a2",
            explanation = "Мат Анастасии: жертва 1. Qxh7+! вскрывает вертикаль h, а конь e7 держит поле g8 — после 1... Kxh7 следует 2. Rh3#.",
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
            preFen = "2kr1b1r/pppnqppp/8/8/5B2/2Q5/PPP1BPPP/2KR4 b - - 0 1",
            prevMoveUci = "c7c6",
            explanation = "Мат Бодена: жертва 1. Qxc6+! bxc6 разрушает прикрытие короля, и перекрёстные слоны матуют ходом 2. Ba6#.",
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
            preFen = "4kb1r/p2nqppp/8/4p1B1/4P3/1Q6/PPP2PPP/2KR4 b - - 0 1",
            prevMoveUci = "e7e6",
            explanation = "Финал «Оперной партии» Морфи: 1. Qb8+! отвлекает коня от поля d8, и после 1... Nxb8 решает 2. Rd8#.",
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
            preFen = "3q2kr/5pp1/8/8/5n2/8/5PPP/3Q1RK1 w - - 0 1",
            prevMoveUci = "d1c1",
            explanation = "Атака Греко по линии h: 1... Ne2+ 2. Kh1 Rxh2+! 3. Kxh2 Qh4# — король полностью лишён прикрытия.",
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
            preFen = "rnb1kb1r/pp3ppp/2p2n2/4q3/4P3/3Q4/PPPB1PPP/2KR1BNR b - - 0 1",
            prevMoveUci = "f6e4",
            explanation = "Партия Рети — Тартаковер (1910): жертва 1. Qd8+! Kxd8 заманивает короля под двойной шах 2. Bg5+, завершая атаку матом.",
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
            preFen = "6k1/3q1ppp/8/3B4/8/8/3R1PPP/6K1 b - - 0 1",
            prevMoveUci = "d7d8",
            explanation = "Ферзь d8 попал под рентген ладьи d2: вскрытый шах 1. Bxf7+! выигрывает темп, и после отхода короля падает ферзь — 2. Rxd8.",
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
            preFen = "1q2k3/ppp2ppp/8/3N4/8/8/PPP2PPP/6K1 b - - 0 1",
            prevMoveUci = "b8a8",
            explanation = "Король e8 и ферзь a8 оказались на полях одного цвета: вилка 1. Nc7+ форсированно выигрывает ферзя 2. Nxa8.",
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
            preFen = "6k1/5pp1/7p/4n3/8/8/1Q4PP/R5K1 w - - 0 1",
            prevMoveUci = "g1f2",
            explanation = "Выход короля на f2 подставил его под королевскую вилку: 1... Nd3+ и 2... Nxb2 выигрывают чистого ферзя.",
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
            preFen = "4rk2/1q3ppp/8/3N4/8/8/5PPP/4R1K1 b - - 0 1",
            prevMoveUci = "b7a6",
            explanation = "Завлечение под вилку: размен 1. Rxe8+ Kxe8 ставит короля на e8 точно под двойной удар 2. Nc7+ и 3. Nxa6.",
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
            preFen = "4r1k1/p1p2pp1/2r4p/8/2PP4/1P4P1/5P1P/5RK1 b - - 0 1",
            prevMoveUci = "e8e6",
            explanation = "Обе ладьи чёрных встали на 6-ю горизонталь через клетку — тычок 1. d5 атакует обе сразу и выигрывает ладью.",
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
            preFen = "8/5pkp/2r3p1/8/5B2/8/5PPP/6K1 b - - 0 1",
            prevMoveUci = "c6c3",
            explanation = "Король g7 и незащищённая ладья c3 оказались на одной диагонали: 1. Be5+ с шахом забирает ладью 2. Bxc3.",
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
            preFen = "4k3/5ppp/8/6r1/8/3Q4/5PPP/6K1 b - - 0 1",
            prevMoveUci = "g5h5",
            explanation = "Незащищённая ладья h5 и раскрытый король e8 ловятся двойным ударом ферзя с шахом и последующим взятием на h5.",
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
            preFen = "4r1k1/5pp1/7p/8/5P2/6KP/1B4P1/2R5 w - - 0 1",
            prevMoveUci = "b2a3",
            explanation = "Слон a3 остался без защиты на 3-й горизонтали с королём g3 — шах 1... Re3+ выигрывает фигуру.",
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
            preFen = "2r1k3/4qppp/8/8/8/6P1/5P1P/R4K2 b - - 0 1",
            prevMoveUci = "e7e6",
            explanation = "Ферзь e6 встал на открытую вертикаль перед собственным королём: 1. Re1 намертво связывает и выигрывает ферзя за ладью.",
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
            preFen = "4k3/3q1ppp/8/8/8/8/4BPPP/4R1K1 b - - 0 1",
            prevMoveUci = "d7e6",
            explanation = "Вскрытие линии с темпом: 1. Bb5+! объявляет шах и одновременно включает ладью e1 против ферзя e6.",
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
            preFen = "4q3/5ppp/8/4k3/8/8/5PPP/R5K1 b - - 0 1",
            prevMoveUci = "e5e4",
            explanation = "Сквозной удар: 1. Re1+ вынуждает короля сойти с линии e, открывая путь к взятию ферзя 2. Rxe8.",
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
            preFen = "6k1/4bppp/8/8/4K3/8/5PPP/Q7 w - - 0 1",
            prevMoveUci = "e4e5",
            explanation = "Король e5 встал на большую диагональ перед своим ферзём: 1... Bf6+ вынуждает отход короля и выигрывает ферзя 2... Bxa1.",
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
            preFen = "8/5k1p/3p2q1/8/8/6QP/5PP1/4R1K1 b - - 0 1",
            prevMoveUci = "g6g7",
            explanation = "Ферзя g7 защищает только король f7: жертва 1. Re7+! отвлекает короля, и на 1... Kxe7 падает ферзь 2. Qxg7+.",
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
            preFen = "3r2k1/5pp1/5n1p/8/8/5R1P/5PBK/8 b - - 0 1",
            prevMoveUci = "d8d5",
            explanation = "Устранение защитника: ладья d5 держится на одном коне f6 — 1. Rxf6! gxf6 и 2. Bxd5 даёт белым лишнюю фигуру.",
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
            preFen = "8/3P1k2/5q2/8/8/8/6PP/6K1 b - - 0 1",
            prevMoveUci = "f6e6",
            explanation = "Слабое превращение 1. d8=N+! объявляет королевскую вилку королю f7 и ферзю e6 и сразу решает исход.",
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
            preFen = "3r2k1/1P2rpp1/7p/8/8/7P/5PP1/3R2K1 b - - 0 1",
            prevMoveUci = "e7f6",
            explanation = "Уничтожение сторожа поля b8 с шахом: 1. Rxd8+ Kh7 освобождает пешке путь к превращению 2. b8=Q.",
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
            preFen = "2r3k1/5ppp/8/8/4N3/7P/5PP1/6K1 w - - 0 1",
            prevMoveUci = "e4c5",
            explanation = "Конь c5 прыгнул на открытую линию c без единого защитника — 1... Rxc5 забирает чистую фигуру.",
            extraFeatures = listOf("WITH_CAPTURE", "WINS_KNIGHT")
        ),

        // ============================================================
        // ЗАЩИТА КОРОЛЯ (KING_SAFETY)
        // ============================================================
        CuratedPuzzle(
            id = "only_defense_back_rank",
            title = "Точная защита на 1-й горизонтали",
            subtitle = "Отразите вторжение ладьи без потери перевеса",
            fen = "6k1/5ppp/8/8/3Q4/7P/5PP1/1q1r1RK1 w - - 0 1",
            solutionMoves = listOf("f1d1"),
            pattern = TacticalPattern.DEFENSIVE_MOVE,
            theme = TacticalTheme.KING_SAFETY,
            difficultyRating = 32,
            preFen = "3r2k1/5ppp/8/8/3Q4/7P/5PP1/1q3RK1 b - - 0 1",
            prevMoveUci = "d8d1",
            explanation = "Ферзь d4 контролирует поле d1 по вертикали: 1. Rxd1! забирает чистую ладью, а на 1... Qxd1+ решает 2. Qxd1 с лишним ферзём.",
            extraFeatures = listOf("WITH_CAPTURE", "GOAL:DEFENSE")
        ),
        CuratedPuzzle(
            id = "defense_battery_neutralize",
            title = "Ликвидация матовой батареи",
            subtitle = "Отразите двойную угрозу мата на g2 и потери ферзя",
            fen = "7k/6pp/2b5/3Q2q1/8/7P/5PP1/5RK1 w - - 0 1",
            solutionMoves = listOf("d5g5"),
            pattern = TacticalPattern.DEFENSIVE_MOVE,
            theme = TacticalTheme.KING_SAFETY,
            difficultyRating = 28,
            preFen = "7k/1b4pp/8/3Q2q1/8/7P/5PP1/5RK1 b - - 0 1",
            prevMoveUci = "b7c6",
            explanation = "Ходом 1... Bc6 чёрные напали на ферзя и создали угрозу мата Qxg2#, но забыли о защите самого ферзя — 1. Qxg5! снимает все угрозы.",
            extraFeatures = listOf("WITH_CAPTURE", "GOAL:DEFENSE")
        ),
        CuratedPuzzle(
            id = "defense_black_only_move_qd8",
            title = "Хладнокровная защита 8-й горизонтали",
            subtitle = "Отразите вторжение сдвоенных ладей",
            fen = "3R2k1/5ppp/2b2q2/8/8/7P/5PP1/4R1K1 b - - 0 1",
            solutionMoves = listOf("f6d8"),
            pattern = TacticalPattern.DEFENSIVE_MOVE,
            theme = TacticalTheme.KING_SAFETY,
            difficultyRating = 30,
            preFen = "6k1/5ppp/2b2q2/8/8/7P/5PP1/3RR1K1 w - - 0 1",
            prevMoveUci = "d1d8",
            explanation = "Слон c6 проигрывал бы на 1... Bxe8?? 2. Rxe8#, а точное 1... Qxd8! ликвидирует шахующую ладью и держит поле e8 под контролем.",
            extraFeatures = listOf("WITH_CAPTURE", "GOAL:DEFENSE")
        ),
        CuratedPuzzle(
            id = "defense_counter_back_rank",
            title = "Встречный удар вместо пассивной защиты",
            subtitle = "Опровергните связку по вертикали d",
            fen = "3r2k1/5ppp/8/8/3q4/7P/3Q1PP1/4R1K1 w - - 0 1",
            solutionMoves = listOf("d2d4", "d8d4", "e1e8"),
            pattern = TacticalPattern.COUNTER_ATTACK,
            theme = TacticalTheme.KING_SAFETY,
            difficultyRating = 44,
            preFen = "4r1k1/5ppp/8/8/3q4/7P/3Q1PP1/4R1K1 b - - 0 1",
            prevMoveUci = "e8d8",
            explanation = "Связав ферзя ходом 1... Rd8, чёрные ослабили свою 8-ю горизонталь: 1. Qxd4! использует слабость короля — на 1... Rxd4 следует 2. Re8#.",
            extraFeatures = listOf("WITH_CAPTURE", "WITH_CHECK", "GOAL:DEFENSE")
        ),
        CuratedPuzzle(
            id = "defense_black_f6_blocks_diagonal",
            title = "Перекрытие смертельной диагонали",
            subtitle = "Найдите единственную защиту от матовой батареи",
            fen = "1r4k1/5p1p/6p1/8/3Q4/q1B4P/5PPK/8 b - - 0 1",
            solutionMoves = listOf("f7f6"),
            pattern = TacticalPattern.DEFENSIVE_MOVE,
            theme = TacticalTheme.KING_SAFETY,
            difficultyRating = 50,
            preFen = "1r4k1/5p1p/6p1/8/3Q4/q6P/1B3PPK/8 w - - 0 1",
            prevMoveUci = "b2c3",
            explanation = "Батарея Qd4+Bc3 грозит матом на g7 и h8 (на 1... Qf8?? решает 2. Qh8#). Скромное 1... f6! наглухо перекрывает диагональ и сохраняет лишнюю ладью.",
            extraFeatures = listOf("GOAL:DEFENSE")
        ),
        CuratedPuzzle(
            id = "defense_stop_queening_with_check",
            title = "Задержание проходной с темпом",
            subtitle = "Не дайте пешке a2 превратиться в ферзя",
            fen = "4B3/8/8/2bk4/5PP1/6K1/p7/8 w - - 0 1",
            solutionMoves = listOf("e8f7", "d5d4", "f7a2"),
            pattern = TacticalPattern.DEFENSIVE_MOVE,
            theme = TacticalTheme.KING_SAFETY,
            difficultyRating = 56,
            preFen = "4B3/8/8/2bk4/5PP1/p5K1/8/8 b - - 0 1",
            prevMoveUci = "a3a2",
            explanation = "Слон c5 перекрывает доступ к полю a1, но промежуточный шах 1. Bf7+! выигрывает решающий темп и переводит слона на диагональ a2–g8: 2. Bxa2.",
            extraFeatures = listOf("WITH_CHECK", "ENDGAME", "GOAL:DEFENSE")
        ),
        CuratedPuzzle(
            id = "defense_forced_stalemate",
            title = "Спасительный пат",
            subtitle = "Спасите безнадёжную позицию при угрозе мата в 1 ход",
            fen = "1R4bk/7p/8/8/6p1/6Pp/5q1P/7K w - - 0 1",
            solutionMoves = listOf("b8g8"),
            pattern = TacticalPattern.STALEMATE_TRICK,
            theme = TacticalTheme.KING_SAFETY,
            difficultyRating = 62,
            preFen = "1R4bk/7p/8/8/6p1/6Pp/q6P/7K b - - 0 1",
            prevMoveUci = "a2f2",
            explanation = "Чёрные грозят матом Qf1#, а белый король и пешки полностью обездвижены: жертва 1. Rxg8+! вынуждает единственный ответ 1... Kxg8 — на доске пат!",
            extraFeatures = listOf("WITH_CHECK", "WITH_CAPTURE", "ENDGAME", "GOAL:DEFENSE")
        )
    )

    fun currentEpochDay(nowMillis: Long = System.currentTimeMillis()): Long {
        return Math.floorDiv(nowMillis, MILLIS_PER_DAY)
    }

    /**
     * Детерминированно возвращает задачу дня для заданного [epochDay].
     * Шаг автоматически подбирается взаимно простым с размером банка, поэтому
     * все задачи гарантированно чередуются без повторов на протяжении полного цикла.
     */
    fun getDailyPuzzle(epochDay: Long = currentEpochDay()): CuratedPuzzle {
        val count = puzzles.size
        val step = coprimeStepFor(count)
        val index = Math.floorMod(epochDay * step + CYCLE_OFFSET, count.toLong()).toInt()
        return puzzles[index]
    }

    private fun coprimeStepFor(count: Int): Long {
        if (count <= 1) return 1L
        var candidate = CYCLE_STEP.coerceAtLeast(3)
        while (gcd(candidate, count) != 1) {
            candidate += 2
        }
        return candidate.toLong()
    }

    private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

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
