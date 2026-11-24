// domain/opening/OpeningDatabase.kt
package com.example.chessmentor.domain.opening

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.move.MoveList
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Результат проверки теоретического (книжного) хода.
 */
data class BookMoveMatch(
    val opening: OpeningPosition?,
    val eco: String?,
    val openingName: String?,
    val isTabiya: Boolean
)

/**
 * База знаний о шахматных дебютах и дерево теоретических ходов.
 */
object OpeningDatabase {

    // Кэш для быстрого поиска (ключ — расстановка фигур, 1-е поле FEN)
    private val fenIndex = mutableMapOf<String, OpeningPosition>()
    private val ecoIndex = mutableMapOf<String, MutableList<OpeningPosition>>()

    // Точное дерево дебютной теории (ключ — "<boardFen> <activeSide>")
    private val exactBookOpenings = mutableMapOf<String, OpeningPosition>()
    private val bookPrefixPositions = mutableSetOf<String>()

    /**
     * Основная база дебютов
     */
    private val openings = listOf(

        // ============================================================
        // ОТКРЫТЫЕ ДЕБЮТЫ (1.e4 e5)
        // ============================================================

        OpeningPosition(
            id = "C50_italian",
            fenPattern = "r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R",
            name = "Итальянская партия",
            nameEn = "Italian Game",
            eco = "C50",
            moves = listOf("e4", "e5", "Nf3", "Nc6", "Bc4"),
            moveCount = 5,
            isMainLine = true,
            characteristics = setOf(
                OpeningCharacteristic.CLASSICAL_CENTER,
                OpeningCharacteristic.QUICK_DEVELOPMENT,
                OpeningCharacteristic.OPEN_CENTER,
                OpeningCharacteristic.TACTICAL
            ),
            keyIdeas = listOf(
                "Быстрое развитие лёгких фигур",
                "Давление на f7",
                "Подготовка к рокировке"
            )
        ),

        OpeningPosition(
            id = "C44_scotch",
            fenPattern = "r1bqkbnr/pppp1ppp/2n5/8/3pP3/5N2/PPP2PPP/RNBQKB1R",
            name = "Шотландская партия",
            nameEn = "Scotch Game",
            eco = "C44",
            moves = listOf("e4", "e5", "Nf3", "Nc6", "d4", "exd4"),
            moveCount = 6,
            isMainLine = true,
            characteristics = setOf(
                OpeningCharacteristic.OPEN_CENTER,
                OpeningCharacteristic.TACTICAL,
                OpeningCharacteristic.QUICK_DEVELOPMENT
            ),
            keyIdeas = listOf(
                "Открытие центра",
                "Активная игра фигур",
                "Инициатива белых"
            )
        ),

        OpeningPosition(
            id = "C55_two_knights",
            fenPattern = "r1bqkb1r/pppp1ppp/2n2n2/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R",
            name = "Защита двух коней",
            nameEn = "Two Knights Defense",
            eco = "C55",
            moves = listOf("e4", "e5", "Nf3", "Nc6", "Bc4", "Nf6"),
            moveCount = 6,
            isMainLine = true,
            characteristics = setOf(
                OpeningCharacteristic.TACTICAL,
                OpeningCharacteristic.OPEN_GAME,
                OpeningCharacteristic.AGGRESSIVE
            ),
            keyIdeas = listOf(
                "Активная защита",
                "Контригра в центре",
                "Тактические осложнения"
            )
        ),

        OpeningPosition(
            id = "C42_petrov",
            fenPattern = "rnbqkb1r/pppp1ppp/5n2/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R",
            name = "Русская партия",
            nameEn = "Petrov's Defense",
            eco = "C42",
            moves = listOf("e4", "e5", "Nf3", "Nf6"),
            moveCount = 4,
            isMainLine = true,
            characteristics = setOf(
                OpeningCharacteristic.SYMMETRICAL,
                OpeningCharacteristic.POSITIONAL,
                OpeningCharacteristic.SOLID_DEFENSE
            ),
            keyIdeas = listOf(
                "Симметричная структура",
                "Надёжная защита",
                "Позиционная игра"
            )
        ),

        OpeningPosition(
            id = "C65_ruy_lopez",
            fenPattern = "r1bqkbnr/pppp1ppp/2n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R",
            name = "Испанская партия",
            nameEn = "Ruy Lopez",
            eco = "C65",
            moves = listOf("e4", "e5", "Nf3", "Nc6", "Bb5"),
            moveCount = 5,
            isMainLine = true,
            characteristics = setOf(
                OpeningCharacteristic.CLASSICAL_CENTER,
                OpeningCharacteristic.POSITIONAL,
                OpeningCharacteristic.SLOW_MANEUVERING
            ),
            keyIdeas = listOf(
                "Давление на коня c6",
                "Подготовка d4",
                "Долгосрочный план"
            )
        ),

        // ============================================================
        // ПОЛУОТКРЫТЫЕ ДЕБЮТЫ (1.e4 без e5)
        // ============================================================

        OpeningPosition(
            id = "B01_scandinavian",
            fenPattern = "rnbqkbnr/ppp1pppp/8/3p4/4P3/8/PPPP1PPP/RNBQKBNR",
            name = "Скандинавская защита",
            nameEn = "Scandinavian Defense",
            eco = "B01",
            moves = listOf("e4", "d5"),
            moveCount = 2,
            isMainLine = true,
            characteristics = setOf(
                OpeningCharacteristic.EARLY_QUEEN_DEVELOPMENT,
                OpeningCharacteristic.SEMI_OPEN,
                OpeningCharacteristic.COUNTER_ATTACK
            ),
            commonMistakes = listOf(
                "Слишком долго гонять ферзя"
            ),
            keyIdeas = listOf(
                "Ферзь выходит рано (Qd5-a5) — это теория!",
                "Быстрое развитие",
                "Контригра"
            )
        ),

        OpeningPosition(
            id = "B02_alekhine",
            fenPattern = "rnbqkb1r/pppppppp/5n2/8/4P3/8/PPPP1PPP/RNBQKBNR",
            name = "Защита Алехина",
            nameEn = "Alekhine's Defense",
            eco = "B02",
            moves = listOf("e4", "Nf6"),
            moveCount = 2,
            isMainLine = true,
            characteristics = setOf(
                OpeningCharacteristic.HYPERMODERN,
                OpeningCharacteristic.PROVOCATIVE,
                OpeningCharacteristic.COUNTER_ATTACK
            ),
            keyIdeas = listOf(
                "Конь провоцирует белых занять центр",
                "Атака на пешечный центр позже",
                "Гипермодернистская идея"
            )
        ),

        OpeningPosition(
            id = "C00_french",
            fenPattern = "rnbqkbnr/pppp1ppp/4p3/8/4P3/8/PPPP1PPP/RNBQKBNR",
            name = "Французская защита",
            nameEn = "French Defense",
            eco = "C00",
            moves = listOf("e4", "e6"),
            moveCount = 2,
            isMainLine = true,
            characteristics = setOf(
                OpeningCharacteristic.CLOSED_CENTER,
                OpeningCharacteristic.POSITIONAL,
                OpeningCharacteristic.SOLID_DEFENSE
            ),
            keyIdeas = listOf(
                "Закрытый центр",
                "Подрыв через d5",
                "Проблемный слон c8"
            )
        ),

        OpeningPosition(
            id = "B20_sicilian",
            fenPattern = "rnbqkbnr/pp1ppppp/8/2p5/4P3/8/PPPP1PPP/RNBQKBNR",
            name = "Сицилианская защита",
            nameEn = "Sicilian Defense",
            eco = "B20",
            moves = listOf("e4", "c5"),
            moveCount = 2,
            isMainLine = true,
            characteristics = setOf(
                OpeningCharacteristic.SEMI_OPEN,
                OpeningCharacteristic.WING_ATTACK,
                OpeningCharacteristic.TACTICAL,
                OpeningCharacteristic.ASYMMETRICAL
            ),
            keyIdeas = listOf(
                "Асимметричная структура",
                "Борьба за центр с фланга",
                "Острая тактическая игра"
            )
        ),

        OpeningPosition(
            id = "B10_caro_kann",
            fenPattern = "rnbqkbnr/pp1ppppp/2p5/8/4P3/8/PPPP1PPP/RNBQKBNR",
            name = "Защита Каро-Канн",
            nameEn = "Caro-Kann Defense",
            eco = "B10",
            moves = listOf("e4", "c6"),
            moveCount = 2,
            isMainLine = true,
            characteristics = setOf(
                OpeningCharacteristic.POSITIONAL,
                OpeningCharacteristic.SOLID_DEFENSE,
                OpeningCharacteristic.DELAYED_DEVELOPMENT
            ),
            keyIdeas = listOf(
                "Надёжная защита",
                "Развитие слона c8",
                "Прочная структура"
            )
        ),

        OpeningPosition(
            id = "B00_pirc",
            fenPattern = "rnbqkb1r/ppp1pppp/3p1n2/8/3PP3/8/PPP2PPP/RNBQKBNR",
            name = "Защита Пирца",
            nameEn = "Pirc Defense",
            eco = "B00",
            moves = listOf("e4", "d6", "d4", "Nf6"),
            moveCount = 4,
            isMainLine = true,
            characteristics = setOf(
                OpeningCharacteristic.HYPERMODERN,
                OpeningCharacteristic.FIANCHETTO,
                OpeningCharacteristic.FLEXIBLE
            ),
            keyIdeas = listOf(
                "Фианкетто королевского слона",
                "Контроль центра фигурами",
                "Гибкая игра"
            )
        ),

        // ============================================================
        // ЗАКРЫТЫЕ ДЕБЮТЫ (1.d4 d5)
        // ============================================================

        OpeningPosition(
            id = "D00_queens_pawn",
            fenPattern = "rnbqkbnr/ppp1pppp/8/3p4/3P4/8/PPP1PPPP/RNBQKBNR",
            name = "Ферзевые пешки",
            nameEn = "Queen's Pawn Game",
            eco = "D00",
            moves = listOf("d4", "d5"),
            moveCount = 2,
            isMainLine = true,
            characteristics = setOf(
                OpeningCharacteristic.CLASSICAL_CENTER,
                OpeningCharacteristic.POSITIONAL
            ),
            keyIdeas = listOf(
                "Классический центр",
                "Позиционная игра"
            )
        ),

        OpeningPosition(
            id = "D06_queens_gambit",
            fenPattern = "rnbqkbnr/ppp1pppp/8/3p4/2PP4/8/PP2PPPP/RNBQKBNR",
            name = "Ферзевый гамбит",
            nameEn = "Queen's Gambit",
            eco = "D06",
            moves = listOf("d4", "d5", "c4"),
            moveCount = 3,
            isMainLine = true,
            characteristics = setOf(
                OpeningCharacteristic.GAMBIT,
                OpeningCharacteristic.POSITIONAL,
                OpeningCharacteristic.CLASSICAL_CENTER
            ),
            keyIdeas = listOf(
                "Не настоящий гамбит",
                "Борьба за центр",
                "Позиционное давление"
            )
        ),

        OpeningPosition(
            id = "D30_queens_gambit_declined",
            fenPattern = "rnbqkbnr/ppp2ppp/4p3/3p4/2PP4/8/PP2PPPP/RNBQKBNR",
            name = "Отказанный ферзевый гамбит",
            nameEn = "Queen's Gambit Declined",
            eco = "D30",
            moves = listOf("d4", "d5", "c4", "e6"),
            moveCount = 4,
            isMainLine = true,
            characteristics = setOf(
                OpeningCharacteristic.POSITIONAL,
                OpeningCharacteristic.CLOSED_CENTER,
                OpeningCharacteristic.SOLID_DEFENSE
            ),
            keyIdeas = listOf(
                "Прочная позиция",
                "Закрытый центр",
                "Долгая игра"
            )
        ),

        OpeningPosition(
            id = "D20_queens_gambit_accepted",
            fenPattern = "rnbqkbnr/ppp1pppp/8/8/2pP4/8/PP2PPPP/RNBQKBNR",
            name = "Принятый ферзевый гамбит",
            nameEn = "Queen's Gambit Accepted",
            eco = "D20",
            moves = listOf("d4", "d5", "c4", "dxc4"),
            moveCount = 4,
            isMainLine = true,
            characteristics = setOf(
                OpeningCharacteristic.GAMBIT,
                OpeningCharacteristic.TACTICAL,
                OpeningCharacteristic.OPEN_CENTER
            ),
            keyIdeas = listOf(
                "Чёрные принимают гамбит",
                "Белые получают центр",
                "Пешку можно отыграть"
            )
        ),

        OpeningPosition(
            id = "D08_slav",
            fenPattern = "rnbqkbnr/pp2pppp/2p5/3p4/2PP4/8/PP2PPPP/RNBQKBNR",
            name = "Славянская защита",
            nameEn = "Slav Defense",
            eco = "D08",
            moves = listOf("d4", "d5", "c4", "c6"),
            moveCount = 4,
            isMainLine = true,
            characteristics = setOf(
                OpeningCharacteristic.SOLID_DEFENSE,
                OpeningCharacteristic.POSITIONAL
            ),
            keyIdeas = listOf(
                "Защита пешки d5",
                "Развитие слона c8",
                "Надёжная игра"
            )
        ),

        // ============================================================
        // ИНДИЙСКИЕ ЗАЩИТЫ (1.d4 Nf6)
        // ============================================================

        OpeningPosition(
            id = "E60_kings_indian",
            fenPattern = "rnbqkb1r/pppppp1p/5np1/8/2PP4/8/PP2PPPP/RNBQKBNR",
            name = "Королевский индийский",
            nameEn = "King's Indian Defense",
            eco = "E60",
            moves = listOf("d4", "Nf6", "c4", "g6"),
            moveCount = 4,
            isMainLine = true,
            characteristics = setOf(
                OpeningCharacteristic.HYPERMODERN,
                OpeningCharacteristic.FIANCHETTO,
                OpeningCharacteristic.KINGSIDE_ATTACK
            ),
            keyIdeas = listOf(
                "Фианкетто королевского слона",
                "Контроль центра фигурами",
                "Атака на королевском фланге"
            )
        ),

        OpeningPosition(
            id = "E15_queens_indian",
            fenPattern = "rnbqkb1r/p1pppppp/1p3n2/8/2PP4/8/PP2PPPP/RNBQKBNR",
            name = "Ферзевый индийский",
            nameEn = "Queen's Indian Defense",
            eco = "E15",
            moves = listOf("d4", "Nf6", "c4", "b6"),
            moveCount = 4,
            isMainLine = true,
            characteristics = setOf(
                OpeningCharacteristic.HYPERMODERN,
                OpeningCharacteristic.FIANCHETTO,
                OpeningCharacteristic.POSITIONAL
            ),
            keyIdeas = listOf(
                "Фианкетто ферзевого слона",
                "Контроль центра с фланга",
                "Гибкая структура"
            )
        ),

        OpeningPosition(
            id = "E20_nimzo_indian",
            fenPattern = "rnbqk2r/pppp1ppp/4pn2/8/1bPP4/2N5/PP2PPPP/R1BQKBNR",
            name = "Защита Нимцовича",
            nameEn = "Nimzo-Indian Defense",
            eco = "E20",
            moves = listOf("d4", "Nf6", "c4", "e6", "Nc3", "Bb4"),
            moveCount = 6,
            isMainLine = true,
            characteristics = setOf(
                OpeningCharacteristic.HYPERMODERN,
                OpeningCharacteristic.POSITIONAL,
                OpeningCharacteristic.TACTICAL
            ),
            keyIdeas = listOf(
                "Связка коня c3",
                "Борьба за центр",
                "Тактические возможности"
            )
        ),

        OpeningPosition(
            id = "A48_london",
            fenPattern = "rnbqkb1r/ppp1pppp/5n2/3p4/3P1B2/5N2/PPP1PPPP/RN1QKB1R",
            name = "Лондонская система",
            nameEn = "London System",
            eco = "A48",
            moves = listOf("d4", "Nf6", "Nf3", "d5", "Bf4"),
            moveCount = 5,
            isMainLine = true,
            characteristics = setOf(
                OpeningCharacteristic.POSITIONAL,
                OpeningCharacteristic.SOLID_DEFENSE,
                OpeningCharacteristic.FLEXIBLE
            ),
            keyIdeas = listOf(
                "Простое развитие",
                "Прочная структура",
                "Понятный план"
            )
        ),

        OpeningPosition(
            id = "E00_catalan",
            fenPattern = "rnbqkb1r/pppp1ppp/4pn2/8/2PP4/6P1/PP2PP1P/RNBQKBNR",
            name = "Каталонское начало",
            nameEn = "Catalan Opening",
            eco = "E00",
            moves = listOf("d4", "Nf6", "c4", "e6", "g3"),
            moveCount = 5,
            isMainLine = true,
            characteristics = setOf(
                OpeningCharacteristic.HYPERMODERN,
                OpeningCharacteristic.FIANCHETTO,
                OpeningCharacteristic.POSITIONAL
            ),
            keyIdeas = listOf(
                "Фианкетто королевского слона",
                "Давление на большую диагональ",
                "Позиционная игра"
            )
        ),

        // ============================================================
        // ФЛАНГОВЫЕ ДЕБЮТЫ
        // ============================================================

        OpeningPosition(
            id = "A04_reti",
            fenPattern = "rnbqkbnr/pppppppp/8/8/8/5N2/PPPPPPPP/RNBQKB1R",
            name = "Начало Рети",
            nameEn = "Reti Opening",
            eco = "A04",
            moves = listOf("Nf3"),
            moveCount = 1,
            isMainLine = true,
            characteristics = setOf(
                OpeningCharacteristic.HYPERMODERN,
                OpeningCharacteristic.FIANCHETTO,
                OpeningCharacteristic.FLEXIBLE
            ),
            keyIdeas = listOf(
                "Гипермодернистский подход",
                "Контроль центра фигурами",
                "Гибкая стратегия"
            )
        ),

        OpeningPosition(
            id = "A10_english",
            fenPattern = "rnbqkbnr/pppppppp/8/8/2P5/8/PP1PPPPP/RNBQKBNR",
            name = "Английское начало",
            nameEn = "English Opening",
            eco = "A10",
            moves = listOf("c4"),
            moveCount = 1,
            isMainLine = true,
            characteristics = setOf(
                OpeningCharacteristic.HYPERMODERN,
                OpeningCharacteristic.WING_ATTACK,
                OpeningCharacteristic.FLEXIBLE
            ),
            keyIdeas = listOf(
                "Фланговая игра",
                "Контроль центра с фланга",
                "Очень гибкая система"
            )
        ),

        OpeningPosition(
            id = "A40_modern_defense",
            fenPattern = "rnbqkbnr/pppppp1p/6p1/8/3P4/8/PPP1PPPP/RNBQKBNR",
            name = "Современная защита",
            nameEn = "Modern Defense",
            eco = "A40",
            moves = listOf("d4", "g6"),
            moveCount = 2,
            isMainLine = true,
            characteristics = setOf(
                OpeningCharacteristic.HYPERMODERN,
                OpeningCharacteristic.FIANCHETTO,
                OpeningCharacteristic.FLEXIBLE
            ),
            keyIdeas = listOf(
                "Фианкетто королевского слона",
                "Контроль центра фигурами",
                "Атака на центр позже"
            )
        ),

        // ============================================================
        // ГАМБИТЫ
        // ============================================================

        OpeningPosition(
            id = "C30_kings_gambit",
            fenPattern = "rnbqkbnr/pppp1ppp/8/4p3/4PP2/8/PPPP2PP/RNBQKBNR",
            name = "Королевский гамбит",
            nameEn = "King's Gambit",
            eco = "C30",
            moves = listOf("e4", "e5", "f4"),
            moveCount = 3,
            isMainLine = true,
            characteristics = setOf(
                OpeningCharacteristic.GAMBIT,
                OpeningCharacteristic.TACTICAL,
                OpeningCharacteristic.PIECE_SACRIFICE,
                OpeningCharacteristic.AGGRESSIVE
            ),
            commonMistakes = listOf(
                "Слишком агрессивная атака без подготовки"
            ),
            keyIdeas = listOf(
                "Жертва пешки f4",
                "Открытие линий",
                "Атака на короля"
            )
        ),

        OpeningPosition(
            id = "D00_blackmar_diemer",
            fenPattern = "rnbqkbnr/ppp1pppp/8/3p4/3PP3/8/PPP2PPP/RNBQKBNR",
            name = "Гамбит Блэкмара-Димера",
            nameEn = "Blackmar-Diemer Gambit",
            eco = "D00",
            moves = listOf("d4", "d5", "e4"),
            moveCount = 3,
            isMainLine = false,
            characteristics = setOf(
                OpeningCharacteristic.GAMBIT,
                OpeningCharacteristic.AGGRESSIVE,
                OpeningCharacteristic.TACTICAL
            ),
            keyIdeas = listOf(
                "Жертва пешки за инициативу",
                "Быстрое развитие",
                "Атака на короля"
            )
        ),

        OpeningPosition(
            id = "C21_danish_gambit",
            fenPattern = "rnbqkbnr/pppp1ppp/8/8/3pP3/2P5/PP3PPP/RNBQKBNR",
            name = "Датский гамбит",
            nameEn = "Danish Gambit",
            eco = "C21",
            moves = listOf("e4", "e5", "d4", "exd4", "c3"),
            moveCount = 5,
            isMainLine = false,
            characteristics = setOf(
                OpeningCharacteristic.GAMBIT,
                OpeningCharacteristic.PIECE_SACRIFICE,
                OpeningCharacteristic.AGGRESSIVE,
                OpeningCharacteristic.TACTICAL
            ),
            keyIdeas = listOf(
                "Жертва двух пешек!",
                "Быстрое развитие и атака",
                "Очень рискованно"
            )
        ),

        OpeningPosition(
            id = "C50_evans_gambit",
            fenPattern = "r1bqk1nr/pppp1ppp/2n5/2b1p3/1PB1P3/5N2/P1PP1PPP/RNBQK2R",
            name = "Гамбит Эванса",
            nameEn = "Evans Gambit",
            eco = "C50",
            moves = listOf("e4", "e5", "Nf3", "Nc6", "Bc4", "Bc5", "b4"),
            moveCount = 7,
            isMainLine = false,
            characteristics = setOf(
                OpeningCharacteristic.GAMBIT,
                OpeningCharacteristic.TACTICAL,
                OpeningCharacteristic.AGGRESSIVE
            ),
            keyIdeas = listOf(
                "Жертва пешки b4",
                "Быстрое развитие центральных пешек",
                "Атака на короля"
            )
        ),

        // ============================================================
        // РАСШИРЕННАЯ БАЗА: ФЕРЗЕВЫЕ, ИНДИЙСКИЕ, ФЛАНГОВЫЕ И ПОЛУОТКРЫТЫЕ (A00-E99)
        // ============================================================

        OpeningPosition(
            id = "A00_sokolsky",
            fenPattern = "rnbqkbnr/pppppppp/8/8/1P6/8/P1PPPPPP/RNBQKBNR",
            name = "Дебют Сокольского",
            nameEn = "Polish Opening",
            eco = "A00",
            moves = listOf("b4"),
            moveCount = 1,
            isMainLine = false,
            characteristics = setOf(OpeningCharacteristic.WING_ATTACK, OpeningCharacteristic.HYPERMODERN, OpeningCharacteristic.FLEXIBLE),
            keyIdeas = listOf("Фланговое фианкетто слона на b2", "Нестандартная борьба за диагональ a1-h8")
        ),

        OpeningPosition(
            id = "A00_benko",
            fenPattern = "rnbqkbnr/pppppppp/8/8/8/6P1/PPPPPP1P/RNBQKBNR",
            name = "Дебют Бенко",
            nameEn = "King's Fianchetto Opening",
            eco = "A00",
            moves = listOf("g3"),
            moveCount = 1,
            isMainLine = false,
            characteristics = setOf(OpeningCharacteristic.FIANCHETTO, OpeningCharacteristic.HYPERMODERN, OpeningCharacteristic.FLEXIBLE),
            keyIdeas = listOf("Фианкетто королевского слона", "Гибкий переход в Каталон или Рети")
        ),

        OpeningPosition(
            id = "A01_larsen",
            fenPattern = "rnbqkbnr/pppppppp/8/8/8/1P6/P1PPPPPP/RNBQKBNR",
            name = "Дебют Ларсена",
            nameEn = "Nimzo-Larsen Attack",
            eco = "A01",
            moves = listOf("b3"),
            moveCount = 1,
            isMainLine = true,
            characteristics = setOf(OpeningCharacteristic.FIANCHETTO, OpeningCharacteristic.HYPERMODERN, OpeningCharacteristic.FLEXIBLE),
            keyIdeas = listOf("Контроль полей e5 и d4 слоном с b2", "Гибкое развитие ферзевого фланга")
        ),

        OpeningPosition(
            id = "A02_bird",
            fenPattern = "rnbqkbnr/pppppppp/8/8/5P2/8/PPPPP1PP/RNBQKBNR",
            name = "Дебют Берда",
            nameEn = "Bird's Opening",
            eco = "A02",
            moves = listOf("f4"),
            moveCount = 1,
            isMainLine = true,
            characteristics = setOf(OpeningCharacteristic.WING_ATTACK, OpeningCharacteristic.KINGSIDE_ATTACK, OpeningCharacteristic.ASYMMETRICAL),
            keyIdeas = listOf("Контроль поля e5", "Подготовка атаки на королевском фланге")
        ),

        OpeningPosition(
            id = "A06_reti_d5",
            fenPattern = "rnbqkbnr/ppp1pppp/8/3p4/8/5N2/PPPPPPPP/RNBQKB1R",
            name = "Дебют Рети",
            nameEn = "Reti Opening",
            eco = "A06",
            moves = listOf("Nf3", "d5"),
            moveCount = 2,
            isMainLine = true,
            characteristics = setOf(OpeningCharacteristic.HYPERMODERN, OpeningCharacteristic.FIANCHETTO, OpeningCharacteristic.FLEXIBLE),
            keyIdeas = listOf("Подрыв центра ходом c4 и фианкетто слона g2", "Фигурное давление на d5")
        ),

        OpeningPosition(
            id = "A13_english_e6",
            fenPattern = "rnbqkbnr/pppp1ppp/4p3/8/2P5/8/PP1PPPPP/RNBQKBNR",
            name = "Английское начало (система e6)",
            nameEn = "English Opening: Agincourt Defense",
            eco = "A13",
            moves = listOf("c4", "e6"),
            moveCount = 2,
            isMainLine = true,
            characteristics = setOf(OpeningCharacteristic.POSITIONAL, OpeningCharacteristic.FLEXIBLE, OpeningCharacteristic.HYPERMODERN),
            keyIdeas = listOf("Возможный переход в Каталон или ферзевый гамбит", "Контроль центра с фланга")
        ),

        OpeningPosition(
            id = "A15_anglo_indian",
            fenPattern = "rnbqkb1r/pppppppp/5n2/8/2P5/8/PP1PPPPP/RNBQKBNR",
            name = "Английское начало (индийский вариант)",
            nameEn = "English Opening: Anglo-Indian Defense",
            eco = "A15",
            moves = listOf("c4", "Nf6"),
            moveCount = 2,
            isMainLine = true,
            characteristics = setOf(OpeningCharacteristic.HYPERMODERN, OpeningCharacteristic.FLEXIBLE, OpeningCharacteristic.POSITIONAL),
            keyIdeas = listOf("Гибкая расстановка обеих сторон", "Борьба за контроль над d4 и e5")
        ),

        OpeningPosition(
            id = "A20_english_e5",
            fenPattern = "rnbqkbnr/pppp1ppp/8/4p3/2P5/8/PP1PPPPP/RNBQKBNR",
            name = "Английское начало (перевёрнутая сицилианская)",
            nameEn = "English Opening: King's English Variation",
            eco = "A20",
            moves = listOf("c4", "e5"),
            moveCount = 2,
            isMainLine = true,
            characteristics = setOf(OpeningCharacteristic.WING_ATTACK, OpeningCharacteristic.ASYMMETRICAL, OpeningCharacteristic.POSITIONAL),
            keyIdeas = listOf("Сицилианская структура за белых с лишним темпом", "Игра на ферзевом фланге")
        ),

        OpeningPosition(
            id = "A30_english_sym",
            fenPattern = "rnbqkbnr/pp1ppppp/8/2p5/2P5/8/PP1PPPPP/RNBQKBNR",
            name = "Английское начало (симметричный вариант)",
            nameEn = "English Opening: Symmetrical Variation",
            eco = "A30",
            moves = listOf("c4", "c5"),
            moveCount = 2,
            isMainLine = true,
            characteristics = setOf(OpeningCharacteristic.SYMMETRICAL, OpeningCharacteristic.POSITIONAL, OpeningCharacteristic.FLEXIBLE),
            keyIdeas = listOf("Борьба за поле d5 и прорыв b4", "Симметричное маневрирование")
        ),

        OpeningPosition(
            id = "A40_horwitz",
            fenPattern = "rnbqkbnr/pppp1ppp/4p3/8/3P4/8/PPP1PPPP/RNBQKBNR",
            name = "Дебют ферзевых пешек: защита Горвица",
            nameEn = "Queen's Pawn Opening: Horwitz Defense",
            eco = "A40",
            moves = listOf("d4", "e6"),
            moveCount = 2,
            isMainLine = true,
            characteristics = setOf(OpeningCharacteristic.FLEXIBLE, OpeningCharacteristic.POSITIONAL, OpeningCharacteristic.SOLID_DEFENSE),
            keyIdeas = listOf("Гибкий переход во Французскую, Голландскую или ферзевый гамбит", "Подготовка d5 или f5")
        ),

        OpeningPosition(
            id = "A40_english_def",
            fenPattern = "rnbqkbnr/p1pppppp/1p6/8/3P4/8/PPP1PPPP/RNBQKBNR",
            name = "Дебют ферзевых пешек: английская защита",
            nameEn = "Queen's Pawn Opening: English Defense",
            eco = "A40",
            moves = listOf("d4", "b6"),
            moveCount = 2,
            isMainLine = false,
            characteristics = setOf(OpeningCharacteristic.HYPERMODERN, OpeningCharacteristic.FIANCHETTO, OpeningCharacteristic.COUNTER_ATTACK),
            keyIdeas = listOf("Фианкетто слона на b7 с давлением на e4 и c2", "Асимметричная контригра")
        ),

        OpeningPosition(
            id = "A40_queens_pawn_nc6",
            fenPattern = "r1bqkbnr/pppppppp/2n5/8/3P4/8/PPP1PPPP/RNBQKBNR",
            name = "Дебют ферзевых пешек: вариант Микенаса",
            nameEn = "Queen's Pawn Opening: Mikenas Defense",
            eco = "A40",
            moves = listOf("d4", "Nc6"),
            moveCount = 2,
            isMainLine = false,
            characteristics = setOf(OpeningCharacteristic.TACTICAL, OpeningCharacteristic.ASYMMETRICAL),
            keyIdeas = listOf("Подготовка e5 или d5", "Нестандартная борьба в центре")
        ),

        OpeningPosition(
            id = "A41_queens_pawn_d6",
            fenPattern = "rnbqkbnr/ppp1pppp/3p4/8/3P4/8/PPP1PPPP/RNBQKBNR",
            name = "Дебют ферзевых пешек (вариант d6)",
            nameEn = "Queen's Pawn Opening: Wade Defense",
            eco = "A41",
            moves = listOf("d4", "d6"),
            moveCount = 2,
            isMainLine = false,
            characteristics = setOf(OpeningCharacteristic.HYPERMODERN, OpeningCharacteristic.FLEXIBLE),
            keyIdeas = listOf("Возможный переход в Староиндийскую или защиту Пирца", "Подготовка e5")
        ),

        OpeningPosition(
            id = "A43_old_benoni",
            fenPattern = "rnbqkbnr/pp1ppppp/8/2p5/3P4/8/PPP1PPPP/RNBQKBNR",
            name = "Старо-Бенони",
            nameEn = "Benoni Defense: Old Benoni",
            eco = "A43",
            moves = listOf("d4", "c5"),
            moveCount = 2,
            isMainLine = true,
            characteristics = setOf(OpeningCharacteristic.ASYMMETRICAL, OpeningCharacteristic.COUNTER_ATTACK, OpeningCharacteristic.TACTICAL),
            keyIdeas = listOf("Немедленный подрыв центра с фланга", "Асимметричная пешечная структура")
        ),

        OpeningPosition(
            id = "A45_indian",
            fenPattern = "rnbqkb1r/pppppppp/5n2/8/3P4/8/PPP1PPPP/RNBQKBNR",
            name = "Индийская защита",
            nameEn = "Indian Defense",
            eco = "A45",
            moves = listOf("d4", "Nf6"),
            moveCount = 2,
            isMainLine = true,
            characteristics = setOf(OpeningCharacteristic.HYPERMODERN, OpeningCharacteristic.FLEXIBLE, OpeningCharacteristic.POSITIONAL),
            keyIdeas = listOf("Контроль поля e4 конём", "Выбор между Нимцовичем, Староиндийской и Грюнфельдом")
        ),

        OpeningPosition(
            id = "A45_trompowsky",
            fenPattern = "rnbqkb1r/pppppppp/5n2/6B1/3P4/8/PPP1PPPP/RN1QKBNR",
            name = "Атака Тромповского",
            nameEn = "Trompowsky Attack",
            eco = "A45",
            moves = listOf("d4", "Nf6", "Bg5"),
            moveCount = 3,
            isMainLine = true,
            characteristics = setOf(OpeningCharacteristic.AGGRESSIVE, OpeningCharacteristic.TACTICAL, OpeningCharacteristic.ASYMMETRICAL),
            keyIdeas = listOf("Давление на коня f6 и угроза сдвоения пешек", "Уклонение от стандартных индийских теорий")
        ),

        OpeningPosition(
            id = "A46_torre",
            fenPattern = "rnbqkb1r/pppp1ppp/4pn2/6B1/3P4/5N2/PPP1PPPP/RN1QKB1R",
            name = "Атака Торре",
            nameEn = "Torre Attack",
            eco = "A46",
            moves = listOf("d4", "Nf6", "Nf3", "e6", "Bg5"),
            moveCount = 5,
            isMainLine = true,
            characteristics = setOf(OpeningCharacteristic.POSITIONAL, OpeningCharacteristic.SOLID_DEFENSE, OpeningCharacteristic.FLEXIBLE),
            keyIdeas = listOf("Связка коня f6 и прочная схема с e3 и c3", "Подготовка прорыва e4")
        ),

        OpeningPosition(
            id = "A50_indian_c4",
            fenPattern = "rnbqkb1r/pppppppp/5n2/8/2PP4/8/PP2PPPP/RNBQKBNR",
            name = "Индийская система (2.c4)",
            nameEn = "Indian Defense: Normal Variation",
            eco = "A50",
            moves = listOf("d4", "Nf6", "c4"),
            moveCount = 3,
            isMainLine = true,
            characteristics = setOf(OpeningCharacteristic.POSITIONAL, OpeningCharacteristic.CLASSICAL_CENTER, OpeningCharacteristic.FLEXIBLE),
            keyIdeas = listOf("Захват пространства на ферзевом фланге", "Ключевой узел закрытых дебютов")
        ),

        OpeningPosition(
            id = "A51_budapest",
            fenPattern = "rnbqkb1r/pppp1ppp/5n2/4p3/2PP4/8/PP2PPPP/RNBQKBNR",
            name = "Будапештский гамбит",
            nameEn = "Budapest Gambit",
            eco = "A51",
            moves = listOf("d4", "Nf6", "c4", "e5"),
            moveCount = 4,
            isMainLine = false,
            characteristics = setOf(OpeningCharacteristic.COUNTER_GAMBIT, OpeningCharacteristic.TACTICAL, OpeningCharacteristic.AGGRESSIVE),
            keyIdeas = listOf("Жертва пешки за быстрое развитие и инициативу", "Давление на ослабленную диагональ и центр")
        ),

        OpeningPosition(
            id = "A53_old_indian",
            fenPattern = "rnbqkb1r/ppp1pppp/3p1n2/8/2PP4/8/PP2PPPP/RNBQKBNR",
            name = "Староиндийское начало",
            nameEn = "Old Indian Defense",
            eco = "A53",
            moves = listOf("d4", "Nf6", "c4", "d6"),
            moveCount = 4,
            isMainLine = true,
            characteristics = setOf(OpeningCharacteristic.SOLID_DEFENSE, OpeningCharacteristic.POSITIONAL, OpeningCharacteristic.FLEXIBLE),
            keyIdeas = listOf("Подготовка e5 без фианкетто", "Крепкая пешечная цепочка")
        ),

        OpeningPosition(
            id = "A56_benoni",
            fenPattern = "rnbqkb1r/pp1ppppp/5n2/2pP4/2P5/8/PP2PPPP/RNBQKBNR",
            name = "Защита Бенони",
            nameEn = "Benoni Defense",
            eco = "A56",
            moves = listOf("d4", "Nf6", "c4", "c5", "d5"),
            moveCount = 5,
            isMainLine = true,
            characteristics = setOf(OpeningCharacteristic.ASYMMETRICAL, OpeningCharacteristic.TACTICAL, OpeningCharacteristic.COUNTER_ATTACK),
            keyIdeas = listOf("Клиновая пешка d5", "Контригра чёрных на ферзевом фланге и по чёрным полям")
        ),

        OpeningPosition(
            id = "A57_benko_gambit",
            fenPattern = "rnbqkb1r/p2ppppp/5n2/1ppP4/2P5/8/PP2PPPP/RNBQKBNR",
            name = "Волжский гамбит (гамбит Бенко)",
            nameEn = "Benko Gambit",
            eco = "A57",
            moves = listOf("d4", "Nf6", "c4", "c5", "d5", "b5"),
            moveCount = 6,
            isMainLine = true,
            characteristics = setOf(OpeningCharacteristic.GAMBIT, OpeningCharacteristic.QUEENSIDE_ATTACK, OpeningCharacteristic.POSITIONAL),
            keyIdeas = listOf("Долгосрочная жертва пешки за полуоткрытые линии a и b", "Позиционное давление на ферзевом фланге")
        ),

        OpeningPosition(
            id = "A80_dutch",
            fenPattern = "rnbqkbnr/ppppp1pp/8/5p2/3P4/8/PPP1PPPP/RNBQKBNR",
            name = "Голландская защита",
            nameEn = "Dutch Defense",
            eco = "A80",
            moves = listOf("d4", "f5"),
            moveCount = 2,
            isMainLine = true,
            characteristics = setOf(OpeningCharacteristic.ASYMMETRICAL, OpeningCharacteristic.KINGSIDE_ATTACK, OpeningCharacteristic.COUNTER_ATTACK),
            keyIdeas = listOf("Борьба за поле e4 и атака на королевском фланге", "Осторожно с ослаблением диагонали e8-h5")
        ),

        OpeningPosition(
            id = "B00_nimzowitsch",
            fenPattern = "r1bqkbnr/pppppppp/2n5/8/4P3/8/PPPP1PPP/RNBQKBNR",
            name = "Дебют Нимцовича",
            nameEn = "Nimzowitsch Defense",
            eco = "B00",
            moves = listOf("e4", "Nc6"),
            moveCount = 2,
            isMainLine = false,
            characteristics = setOf(OpeningCharacteristic.HYPERMODERN, OpeningCharacteristic.ASYMMETRICAL, OpeningCharacteristic.COUNTER_ATTACK),
            keyIdeas = listOf("Провокация пешечного центра и быстрое развитие фигур", "Подготовка d5 или e5")
        ),

        OpeningPosition(
            id = "B06_modern_e4",
            fenPattern = "rnbqkbnr/pppppp1p/6p1/8/4P3/8/PPPP1PPP/RNBQKBNR",
            name = "Современная защита (1.e4 g6)",
            nameEn = "Modern Defense",
            eco = "B06",
            moves = listOf("e4", "g6"),
            moveCount = 2,
            isMainLine = true,
            characteristics = setOf(OpeningCharacteristic.HYPERMODERN, OpeningCharacteristic.FIANCHETTO, OpeningCharacteristic.FLEXIBLE),
            keyIdeas = listOf("Фианкетто слона g7 с давлением на центр", "Гибкое уклонение от ранних стычек")
        ),

        OpeningPosition(
            id = "B21_smith_morra",
            fenPattern = "rnbqkbnr/pp1ppppp/8/8/3pP3/2P5/PP3PPP/RNBQKBNR",
            name = "Сицилианская защита: гамбит Смита-Морра",
            nameEn = "Sicilian Defense: Smith-Morra Gambit",
            eco = "B21",
            moves = listOf("e4", "c5", "d4", "cxd4", "c3"),
            moveCount = 5,
            isMainLine = false,
            characteristics = setOf(OpeningCharacteristic.GAMBIT, OpeningCharacteristic.QUICK_DEVELOPMENT, OpeningCharacteristic.TACTICAL),
            keyIdeas = listOf("Жертва пешки ради открытых линий c и d", "Атака по центру до завершения развития чёрных")
        ),

        OpeningPosition(
            id = "B22_alapin",
            fenPattern = "rnbqkbnr/pp1ppppp/8/2p5/4P3/2P5/PP1P1PPP/RNBQKBNR",
            name = "Сицилианская защита: вариант Алапина",
            nameEn = "Sicilian Defense: Alapin Variation",
            eco = "B22",
            moves = listOf("e4", "c5", "c3"),
            moveCount = 3,
            isMainLine = true,
            characteristics = setOf(OpeningCharacteristic.CLASSICAL_CENTER, OpeningCharacteristic.POSITIONAL, OpeningCharacteristic.SOLID_DEFENSE),
            keyIdeas = listOf("Подготовка мощного центра c3+d4", "Уход от основных острых веток сицилианской")
        ),

        OpeningPosition(
            id = "B23_closed_sicilian",
            fenPattern = "rnbqkbnr/pp1ppppp/8/2p5/4P3/2N5/PPPP1PPP/R1BQKBNR",
            name = "Закрытая сицилианская защита",
            nameEn = "Sicilian Defense: Closed",
            eco = "B23",
            moves = listOf("e4", "c5", "Nc3"),
            moveCount = 3,
            isMainLine = true,
            characteristics = setOf(OpeningCharacteristic.CLOSED_CENTER, OpeningCharacteristic.KINGSIDE_ATTACK, OpeningCharacteristic.SLOW_MANEUVERING),
            keyIdeas = listOf("Развитие с g3, Bg2, d3 и атакой на королевском фланге", "Надёжный контроль поля d5")
        ),

        OpeningPosition(
            id = "B30_rossolimo",
            fenPattern = "r1bqkbnr/pp1ppppp/2n5/1Bp5/4P3/5N2/PPPP1PPP/RNBQK2R",
            name = "Сицилианская защита: вариант Россолимо",
            nameEn = "Sicilian Defense: Rossolimo Variation",
            eco = "B30",
            moves = listOf("e4", "c5", "Nf3", "Nc6", "Bb5"),
            moveCount = 5,
            isMainLine = true,
            characteristics = setOf(OpeningCharacteristic.POSITIONAL, OpeningCharacteristic.EARLY_CASTLING, OpeningCharacteristic.TACTICAL),
            keyIdeas = listOf("Давление на коня c6 и быстрая рокировка", "Позиционная борьба за структуру")
        ),

        OpeningPosition(
            id = "B50_sicilian_d6",
            fenPattern = "rnbqkbnr/pp2pppp/3p4/2p5/4P3/5N2/PPPP1PPP/RNBQKB1R",
            name = "Сицилианская защита (вариант d6)",
            nameEn = "Sicilian Defense: Modern Variations",
            eco = "B50",
            moves = listOf("e4", "c5", "Nf3", "d6"),
            moveCount = 4,
            isMainLine = true,
            characteristics = setOf(OpeningCharacteristic.SEMI_OPEN, OpeningCharacteristic.TACTICAL, OpeningCharacteristic.ASYMMETRICAL),
            keyIdeas = listOf("Подготовка Nf6 и выбор Найдорфа, Дракона или Шевенингена", "Надёжный контроль e5")
        ),

        OpeningPosition(
            id = "C20_kings_pawn",
            fenPattern = "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR",
            name = "Открытый дебют (1.e4 e5)",
            nameEn = "King's Pawn Game",
            eco = "C20",
            moves = listOf("e4", "e5"),
            moveCount = 2,
            isMainLine = true,
            characteristics = setOf(OpeningCharacteristic.CLASSICAL_CENTER, OpeningCharacteristic.OPEN_GAME, OpeningCharacteristic.QUICK_DEVELOPMENT),
            keyIdeas = listOf("Симметричный захват центра пешками", "Быстрое развитие лёгких фигур")
        ),

        OpeningPosition(
            id = "C22_center_game",
            fenPattern = "rnbqkbnr/pppp1ppp/8/8/3QP3/8/PPP2PPP/RNB1KBNR",
            name = "Дебют центра",
            nameEn = "Center Game",
            eco = "C22",
            moves = listOf("e4", "e5", "d4", "exd4", "Qxd4"),
            moveCount = 5,
            isMainLine = false,
            characteristics = setOf(OpeningCharacteristic.OPEN_CENTER, OpeningCharacteristic.EARLY_QUEEN_DEVELOPMENT, OpeningCharacteristic.TACTICAL),
            keyIdeas = listOf("Быстрое вскрытие центра и отвод ферзя на e3", "Подготовка длинной рокировки")
        ),

        OpeningPosition(
            id = "C23_bishop",
            fenPattern = "rnbqkbnr/pppp1ppp/8/4p3/2B1P3/8/PPPP1PPP/RNBQK1NR",
            name = "Дебют слона",
            nameEn = "Bishop's Opening",
            eco = "C23",
            moves = listOf("e4", "e5", "Bc4"),
            moveCount = 3,
            isMainLine = true,
            characteristics = setOf(OpeningCharacteristic.CLASSICAL_CENTER, OpeningCharacteristic.TACTICAL, OpeningCharacteristic.FLEXIBLE),
            keyIdeas = listOf("Давление на пункт f7 без блокировки пешки f", "Возможный переход к Итальянской или Венской партии")
        ),

        OpeningPosition(
            id = "C25_vienna",
            fenPattern = "rnbqkbnr/pppp1ppp/8/4p3/4P3/2N5/PPPP1PPP/R1BQKBNR",
            name = "Венская партия",
            nameEn = "Vienna Game",
            eco = "C25",
            moves = listOf("e4", "e5", "Nc3"),
            moveCount = 3,
            isMainLine = true,
            characteristics = setOf(OpeningCharacteristic.TACTICAL, OpeningCharacteristic.OPEN_GAME, OpeningCharacteristic.FLEXIBLE),
            keyIdeas = listOf("Контроль d5 и подготовка f4 в удобный момент", "Гибкое развитие без ранней потери темпов")
        ),

        OpeningPosition(
            id = "C40_kings_knight",
            fenPattern = "rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R",
            name = "Дебют королевского коня",
            nameEn = "King's Knight Opening",
            eco = "C40",
            moves = listOf("e4", "e5", "Nf3"),
            moveCount = 3,
            isMainLine = true,
            characteristics = setOf(OpeningCharacteristic.CLASSICAL_CENTER, OpeningCharacteristic.QUICK_DEVELOPMENT, OpeningCharacteristic.OPEN_GAME),
            keyIdeas = listOf("Атака на пешку e5 с развитием коня", "Основа Испанской, Итальянской и Шотландской партий")
        ),

        OpeningPosition(
            id = "C41_philidor",
            fenPattern = "rnbqkbnr/ppp2ppp/3p4/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R",
            name = "Защита Филидора",
            nameEn = "Philidor Defense",
            eco = "C41",
            moves = listOf("e4", "e5", "Nf3", "d6"),
            moveCount = 4,
            isMainLine = true,
            characteristics = setOf(OpeningCharacteristic.SOLID_DEFENSE, OpeningCharacteristic.POSITIONAL, OpeningCharacteristic.CLOSED_CENTER),
            keyIdeas = listOf("Прочная защита пешки e5", "Недостаток: временное ограничение слона f8")
        ),

        OpeningPosition(
            id = "C44_ponziani",
            fenPattern = "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/2P2N2/PP1P1PPP/RNBQKB1R",
            name = "Дебют Понциани",
            nameEn = "Ponziani Opening",
            eco = "C44",
            moves = listOf("e4", "e5", "Nf3", "Nc6", "c3"),
            moveCount = 5,
            isMainLine = false,
            characteristics = setOf(OpeningCharacteristic.CLASSICAL_CENTER, OpeningCharacteristic.TACTICAL, OpeningCharacteristic.OPEN_GAME),
            keyIdeas = listOf("Подготовка мощного пешечного центра ходом d4", "Осторожно с контрударами d5 и Nf6")
        ),

        OpeningPosition(
            id = "C46_three_knights",
            fenPattern = "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/2N2N2/PPPP1PPP/R1BQKB1R",
            name = "Дебют трёх коней",
            nameEn = "Three Knights Opening",
            eco = "C46",
            moves = listOf("e4", "e5", "Nf3", "Nc6", "Nc3"),
            moveCount = 5,
            isMainLine = true,
            characteristics = setOf(OpeningCharacteristic.SYMMETRICAL, OpeningCharacteristic.QUICK_DEVELOPMENT),
            keyIdeas = listOf("Гармоничное развитие коней к центру", "Часто переходит в дебют четырёх коней")
        ),

        OpeningPosition(
            id = "C47_four_knights",
            fenPattern = "r1bqkb1r/pppp1ppp/2n2n2/4p3/4P3/2N2N2/PPPP1PPP/R1BQKB1R",
            name = "Дебют четырёх коней",
            nameEn = "Four Knights Game",
            eco = "C47",
            moves = listOf("e4", "e5", "Nf3", "Nc6", "Nc3", "Nf6"),
            moveCount = 6,
            isMainLine = true,
            characteristics = setOf(OpeningCharacteristic.SYMMETRICAL, OpeningCharacteristic.QUICK_DEVELOPMENT, OpeningCharacteristic.POSITIONAL),
            keyIdeas = listOf("Симметричное классическое развитие", "Выбор между Bb5 и d4")
        ),

        OpeningPosition(
            id = "C70_spanish_morphy",
            fenPattern = "r1bqkbnr/1ppp1ppp/p1n5/4p3/B3P3/5N2/PPPP1PPP/RNBQK2R",
            name = "Испанская партия: защита Морфи",
            nameEn = "Ruy Lopez: Morphy Defense",
            eco = "C70",
            moves = listOf("e4", "e5", "Nf3", "Nc6", "Bb5", "a6", "Ba4"),
            moveCount = 7,
            isMainLine = true,
            characteristics = setOf(OpeningCharacteristic.CLASSICAL_CENTER, OpeningCharacteristic.POSITIONAL, OpeningCharacteristic.SLOW_MANEUVERING),
            keyIdeas = listOf("Сохранение давления по диагонали a4-e8", "Подготовка c3, d4 и ладьи на e1")
        ),

        OpeningPosition(
            id = "D01_richter_veresov",
            fenPattern = "rnbqkb1r/ppp1pppp/5n2/3p2B1/3P4/2N5/PPP1PPPP/R2QKBNR",
            name = "Дебют Вересова",
            nameEn = "Richter-Veresov Attack",
            eco = "D01",
            moves = listOf("d4", "d5", "Nc3", "Nf6", "Bg5"),
            moveCount = 5,
            isMainLine = false,
            characteristics = setOf(OpeningCharacteristic.TACTICAL, OpeningCharacteristic.AGGRESSIVE, OpeningCharacteristic.EARLY_CASTLING),
            keyIdeas = listOf("Подготовка f3 и e4 с перевесом в центре", "Быстрая длинная рокировка")
        ),

        OpeningPosition(
            id = "D02_london_d5",
            fenPattern = "rnbqkbnr/ppp1pppp/8/3p4/3P1B2/8/PPP1PPPP/RN1QKBNR",
            name = "Лондонская система",
            nameEn = "London System",
            eco = "D02",
            moves = listOf("d4", "d5", "Bf4"),
            moveCount = 3,
            isMainLine = true,
            characteristics = setOf(OpeningCharacteristic.POSITIONAL, OpeningCharacteristic.SOLID_DEFENSE, OpeningCharacteristic.FLEXIBLE),
            keyIdeas = listOf("Выход чернопольного слона до e3", "Прочная пирамида пешек c3-d4-e3")
        ),

        OpeningPosition(
            id = "D04_colle",
            fenPattern = "rnbqkb1r/ppp1pppp/5n2/3p4/3P4/4PN2/PPP2PPP/RNBQKB1R",
            name = "Система Колле",
            nameEn = "Colle System",
            eco = "D04",
            moves = listOf("d4", "d5", "Nf3", "Nf6", "e3"),
            moveCount = 5,
            isMainLine = true,
            characteristics = setOf(OpeningCharacteristic.SOLID_DEFENSE, OpeningCharacteristic.POSITIONAL, OpeningCharacteristic.KINGSIDE_ATTACK),
            keyIdeas = listOf("Подготовка прорыва e3-e4 с поддержкой Bd3 и Nbd2", "Надёжное развитие без ранних рисков")
        ),

        OpeningPosition(
            id = "D11_slav_nf3",
            fenPattern = "rnbqkbnr/pp2pppp/2p5/3p4/2PP4/5N2/PP2PPPP/RNBQKB1R",
            name = "Славянская защита (3.Nf3)",
            nameEn = "Slav Defense: Quiet Variation",
            eco = "D11",
            moves = listOf("d4", "d5", "c4", "c6", "Nf3"),
            moveCount = 5,
            isMainLine = true,
            characteristics = setOf(OpeningCharacteristic.SOLID_DEFENSE, OpeningCharacteristic.POSITIONAL),
            keyIdeas = listOf("Предупреждение выпада Bf5", "Спокойное развитие по классическим принципам")
        ),

        OpeningPosition(
            id = "D80_grunfeld",
            fenPattern = "rnbqkb1r/ppp1pp1p/5np1/3p4/2PP4/2N5/PP2PPPP/R1BQKBNR",
            name = "Защита Грюнфельда",
            nameEn = "Grunfeld Defense",
            eco = "D80",
            moves = listOf("d4", "Nf6", "c4", "g6", "Nc3", "d5"),
            moveCount = 6,
            isMainLine = true,
            characteristics = setOf(OpeningCharacteristic.HYPERMODERN, OpeningCharacteristic.COUNTER_ATTACK, OpeningCharacteristic.TACTICAL),
            keyIdeas = listOf("Немедленная атака на центр белых и давление по диагонали g7-a1", "Острая фигурная контригра")
        ),

        OpeningPosition(
            id = "E10_blumenfeld",
            fenPattern = "rnbqkb1r/pppp1ppp/4pn2/8/2PP4/5N2/PP2PPPP/RNBQKB1R",
            name = "Индийская система (3.Nf3 e6)",
            nameEn = "Indian Defense: Anti-Nimzo-Indian",
            eco = "E10",
            moves = listOf("d4", "Nf6", "c4", "e6", "Nf3"),
            moveCount = 5,
            isMainLine = true,
            characteristics = setOf(OpeningCharacteristic.POSITIONAL, OpeningCharacteristic.FLEXIBLE),
            keyIdeas = listOf("Уклонение от защиты Нимцовича", "Выбор между Каталоном, Новоиндийской и Ферзевым гамбитом")
        ),

        OpeningPosition(
            id = "E11_bogo_indian",
            fenPattern = "rnbqk2r/pppp1ppp/4pn2/8/1bPP4/5N2/PP2PPPP/RNBQKB1R",
            name = "Защита Боголюбова",
            nameEn = "Bogo-Indian Defense",
            eco = "E11",
            moves = listOf("d4", "Nf6", "c4", "e6", "Nf3", "Bb4+"),
            moveCount = 6,
            isMainLine = true,
            characteristics = setOf(OpeningCharacteristic.POSITIONAL, OpeningCharacteristic.SOLID_DEFENSE, OpeningCharacteristic.EARLY_CASTLING),
            keyIdeas = listOf("Шах слоном и быстрая рокировка чёрных", "Борьба за чёрные центральные поля")
        )
    )

    /**
     * Канонические продолжения главных дебютных вариантов (до 6–10 полуходов),
     * позволяющие честно отмечать теоретические ходы BOOK без ложных срабатываний.
     */
    private data class CanonicalBookLine(
        val eco: String,
        val name: String,
        val moves: List<String>
    )

    private val canonicalBookLines = listOf(
        // Открытые дебюты
        CanonicalBookLine("C54", "Итальянская партия (Giuoco Piano)", listOf("e4", "e5", "Nf3", "Nc6", "Bc4", "Bc5", "c3", "Nf6", "d3", "d6")),
        CanonicalBookLine("C54", "Итальянская партия (центр d4)", listOf("e4", "e5", "Nf3", "Nc6", "Bc4", "Bc5", "c3", "Nf6", "d4", "exd4", "cxd4", "Bb4+")),
        CanonicalBookLine("C50", "Итальянская партия (тихая игра)", listOf("e4", "e5", "Nf3", "Nc6", "Bc4", "Bc5", "d3", "Nf6", "O-O", "d6")),
        CanonicalBookLine("C57", "Защита двух коней", listOf("e4", "e5", "Nf3", "Nc6", "Bc4", "Nf6", "Ng5", "d5", "exd5", "Na5")),
        CanonicalBookLine("C55", "Защита двух коней (спокойная)", listOf("e4", "e5", "Nf3", "Nc6", "Bc4", "Nf6", "O-O", "Be7")),
        CanonicalBookLine("C55", "Защита двух коней (4.d3 Be7)", listOf("e4", "e5", "Nf3", "Nc6", "Bc4", "Nf6", "d3", "Be7", "O-O", "O-O")),
        CanonicalBookLine("C84", "Испанская партия (закрытая)", listOf("e4", "e5", "Nf3", "Nc6", "Bb5", "a6", "Ba4", "Nf6", "O-O", "Be7", "Re1", "b5", "Bb3", "d6")),
        CanonicalBookLine("C65", "Испанская партия (берлинская защита)", listOf("e4", "e5", "Nf3", "Nc6", "Bb5", "Nf6", "O-O", "Nxe4", "d4", "Nd6")),
        CanonicalBookLine("C68", "Испанская партия (разменный вариант)", listOf("e4", "e5", "Nf3", "Nc6", "Bb5", "a6", "Bxc6", "dxc6", "O-O")),
        CanonicalBookLine("C45", "Шотландская партия", listOf("e4", "e5", "Nf3", "Nc6", "d4", "exd4", "Nxd4", "Nf6", "Nxc6", "bxc6")),
        CanonicalBookLine("C47", "Дебют четырёх коней", listOf("e4", "e5", "Nf3", "Nc6", "Nc3", "Nf6", "Bb5", "Bb4", "O-O", "O-O")),
        CanonicalBookLine("C41", "Защита Филидора", listOf("e4", "e5", "Nf3", "d6", "d4", "exd4", "Nxd4", "Nf6", "Nc3", "Be7")),
        CanonicalBookLine("C42", "Русская партия", listOf("e4", "e5", "Nf3", "Nf6", "Nxe5", "d6", "Nf3", "Nxe4", "d4", "d5")),
        CanonicalBookLine("C25", "Венская партия", listOf("e4", "e5", "Nc3", "Nf6", "f4", "d5", "fxe5", "Nxe4")),
        // Полуоткрытые дебюты
        CanonicalBookLine("B90", "Сицилианская защита (вариант Найдорфа)", listOf("e4", "c5", "Nf3", "d6", "d4", "cxd4", "Nxd4", "Nf6", "Nc3", "a6")),
        CanonicalBookLine("B70", "Сицилианская защита (вариант дракона)", listOf("e4", "c5", "Nf3", "d6", "d4", "cxd4", "Nxd4", "Nf6", "Nc3", "g6")),
        CanonicalBookLine("B33", "Сицилианская защита (челябинский вариант)", listOf("e4", "c5", "Nf3", "Nc6", "d4", "cxd4", "Nxd4", "Nf6", "Nc3", "e5")),
        CanonicalBookLine("B44", "Сицилианская защита (вариант Тайманова)", listOf("e4", "c5", "Nf3", "e6", "d4", "cxd4", "Nxd4", "Nc6", "Nc3", "Qc7")),
        CanonicalBookLine("B40", "Сицилианская защита (e6)", listOf("e4", "c5", "Nf3", "e6", "d4", "cxd4", "Nxd4", "a6")),
        CanonicalBookLine("B22", "Сицилианская защита (вариант Алапина)", listOf("e4", "c5", "c3", "Nf6", "e5", "Nd5", "d4", "cxd4", "cxd4")),
        CanonicalBookLine("B23", "Закрытая сицилианская защита", listOf("e4", "c5", "Nc3", "Nc6", "g3", "g6", "Bg2", "Bg7", "d3", "d6")),
        CanonicalBookLine("C02", "Французская защита (продвижение)", listOf("e4", "e6", "d4", "d5", "e5", "c5", "c3", "Nc6", "Nf3", "Qb6")),
        CanonicalBookLine("C15", "Французская защита (вариант Винавера)", listOf("e4", "e6", "d4", "d5", "Nc3", "Bb4", "e5", "c5", "a3", "Bxc3+", "bxc3")),
        CanonicalBookLine("C03", "Французская защита (вариант Тарраша)", listOf("e4", "e6", "d4", "d5", "Nd2", "Nf6", "e5", "Nfd7", "Bd3", "c5")),
        CanonicalBookLine("C01", "Французская защита (разменный вариант)", listOf("e4", "e6", "d4", "d5", "exd5", "exd5", "Nf3", "Nf6", "Bd3")),
        CanonicalBookLine("B12", "Защита Каро-Канн (продвижение)", listOf("e4", "c6", "d4", "d5", "e5", "Bf5", "Nf3", "e6", "Be2", "c5")),
        CanonicalBookLine("B18", "Защита Каро-Канн (классический вариант)", listOf("e4", "c6", "d4", "d5", "Nc3", "dxe4", "Nxe4", "Bf5", "Ng3", "Bg6")),
        CanonicalBookLine("B13", "Защита Каро-Канн (разменный вариант)", listOf("e4", "c6", "d4", "d5", "exd5", "cxd5", "Bd3", "Nc6", "c3", "Nf6")),
        CanonicalBookLine("B14", "Защита Каро-Канн (атака Панова)", listOf("e4", "c6", "d4", "d5", "exd5", "cxd5", "c4", "Nf6", "Nc3")),
        CanonicalBookLine("B01", "Скандинавская защита", listOf("e4", "d5", "exd5", "Qxd5", "Nc3", "Qa5", "d4", "Nf6", "Nf3", "Bf5")),
        CanonicalBookLine("B07", "Защита Пирца-Уфимцева", listOf("e4", "d6", "d4", "Nf6", "Nc3", "g6", "Nf3", "Bg7", "Be2", "O-O")),
        CanonicalBookLine("B02", "Защита Алехина", listOf("e4", "Nf6", "e5", "Nd5", "d4", "d6", "Nf3")),
        // Закрытые, полузакрытые и ферзевые дебюты
        CanonicalBookLine("A40", "Дебют ферзевых пешек: английская защита", listOf("d4", "e6", "c4", "b6", "Nc3", "Bb7")),
        CanonicalBookLine("A40", "Гамбит Энглунда", listOf("d4", "e5", "dxe5", "Nc6", "Nf3", "Qe7")),
        CanonicalBookLine("A45", "Лондонская система (через 1...Nf6)", listOf("d4", "Nf6", "Bf4", "d5", "e3", "e6", "Nf3", "c5", "c3")),
        CanonicalBookLine("D37", "Отказанный ферзевый гамбит", listOf("d4", "d5", "c4", "e6", "Nc3", "Nf6", "Nf3", "Be7", "Bf4", "O-O", "e3")),
        CanonicalBookLine("D35", "Ферзевый гамбит (разменный вариант)", listOf("d4", "d5", "c4", "e6", "Nc3", "Nf6", "cxd5", "exd5", "Bg5", "Be7")),
        CanonicalBookLine("D15", "Славянская защита", listOf("d4", "d5", "c4", "c6", "Nf3", "Nf6", "Nc3", "dxc4", "a4", "Bf5")),
        CanonicalBookLine("D45", "Полуславянская защита", listOf("d4", "d5", "c4", "c6", "Nf3", "Nf6", "Nc3", "e6", "e3", "Nbd7")),
        CanonicalBookLine("D20", "Принятый ферзевый гамбит", listOf("d4", "d5", "c4", "dxc4", "Nf3", "Nf6", "e3", "e6", "Bxc4", "c5")),
        CanonicalBookLine("D02", "Лондонская система", listOf("d4", "d5", "Bf4", "Nf6", "e3", "e6", "Nf3", "c5", "c3", "Nc6")),
        CanonicalBookLine("E92", "Староиндийская защита (классическая)", listOf("d4", "Nf6", "c4", "g6", "Nc3", "Bg7", "e4", "d6", "Nf3", "O-O", "Be2", "e5")),
        CanonicalBookLine("D85", "Защита Грюнфельда", listOf("d4", "Nf6", "c4", "g6", "Nc3", "d5", "cxd5", "Nxd5", "e4", "Nxc3", "bxc3", "Bg7")),
        CanonicalBookLine("E46", "Защита Нимцовича", listOf("d4", "Nf6", "c4", "e6", "Nc3", "Bb4", "e3", "O-O", "Bd3", "d5", "Nf3")),
        CanonicalBookLine("E15", "Новоиндийская защита", listOf("d4", "Nf6", "c4", "e6", "Nf3", "b6", "g3", "Ba6", "b3", "Bb4+")),
        CanonicalBookLine("E06", "Каталонское начало", listOf("d4", "Nf6", "c4", "e6", "g3", "d5", "Bg2", "Be7", "Nf3", "O-O", "O-O")),
        CanonicalBookLine("A81", "Голландская защита (ленинградский вариант)", listOf("d4", "f5", "g3", "Nf6", "Bg2", "g6", "Nf3", "Bg7", "O-O", "O-O")),
        // Фланговые дебюты
        CanonicalBookLine("A28", "Английское начало", listOf("c4", "e5", "Nc3", "Nf6", "Nf3", "Nc6", "g3", "Bb4")),
        CanonicalBookLine("A30", "Английское начало (симметричное)", listOf("c4", "c5", "Nf3", "Nf6", "Nc3", "Nc6", "g3", "g6", "Bg2", "Bg7")),
        CanonicalBookLine("A07", "Дебют Рети", listOf("Nf3", "d5", "g3", "Nf6", "Bg2", "c6", "O-O", "Bf5", "d3")),
        CanonicalBookLine("A09", "Дебют Рети (c4)", listOf("Nf3", "d5", "c4", "d4", "g3", "Nc6", "Bg2", "e5"))
    )

    private val canonicalLineOpenings = mutableMapOf<String, Pair<String, String>>()

    init {
        buildIndexes()
    }

    /**
     * Построение индексов для быстрого поиска и дерева теоретических ходов
     */
    private fun buildIndexes() {
        openings.forEach { opening ->
            fenIndex[extractBoardFen(opening.fenPattern)] = opening
            ecoIndex.getOrPut(opening.eco) { mutableListOf() }.add(opening)
            indexOpeningMoves(opening)
        }
        canonicalBookLines.forEach { line ->
            indexCanonicalLine(line)
        }
    }

    private fun indexOpeningMoves(opening: OpeningPosition) {
        val fens = replaySanLine(opening.moves) ?: return
        for ((idx, fen) in fens.withIndex()) {
            val key = normalizeBookKey(fen)
            if (idx == fens.lastIndex) {
                val prev = exactBookOpenings[key]
                if (prev == null || opening.moveCount >= prev.moveCount) {
                    exactBookOpenings[key] = opening
                }
            } else {
                bookPrefixPositions.add(key)
            }
        }
    }

    private fun indexCanonicalLine(line: CanonicalBookLine) {
        val fens = replaySanLine(line.moves) ?: return
        for ((idx, fen) in fens.withIndex()) {
            val key = normalizeBookKey(fen)
            if (idx == fens.lastIndex) {
                canonicalLineOpenings.putIfAbsent(key, line.eco to line.name)
            } else {
                bookPrefixPositions.add(key)
            }
        }
    }

    private fun replaySanLine(sanMoves: List<String>): List<String>? {
        if (sanMoves.isEmpty()) return null
        return runCatching {
            val board = Board()
            val fens = ArrayList<String>(sanMoves.size)
            for (san in sanMoves) {
                val moveList = MoveList(board.fen)
                moveList.addSanMove(san, true, true)
                val move = moveList.firstOrNull() ?: return null
                if (!board.legalMoves().contains(move) || !board.doMove(move)) return null
                fens.add(board.fen)
            }
            fens
        }.getOrNull()
    }

    /**
     * Проверяет, принадлежит ли позиция ПОСЛЕ хода [fenAfter] точному дереву дебютной теории.
     * Не использует нечёткое сходство — только реальные теоретические позиции и перестановки ходов.
     */
    fun matchBookMove(fenAfter: String): BookMoveMatch? {
        val key = normalizeBookKey(fenAfter)
        if (key.isEmpty()) return null
        val boardFen = extractBoardFen(fenAfter)
        if (expandBoardFen(boardFen) == START_EXPANDED) return null

        exactBookOpenings[key]?.let { opening ->
            return BookMoveMatch(
                opening = opening,
                eco = opening.eco,
                openingName = opening.name,
                isTabiya = true
            )
        }

        canonicalLineOpenings[key]?.let { (eco, name) ->
            return BookMoveMatch(
                opening = fenIndex[boardFen],
                eco = eco,
                openingName = name,
                isTabiya = true
            )
        }

        if (key in bookPrefixPositions) {
            return BookMoveMatch(
                opening = null,
                eco = null,
                openingName = null,
                isTabiya = false
            )
        }

        // Резервная проверка точного совпадения расстановки фигур для именованной табии
        fenIndex[boardFen]?.let { opening ->
            return BookMoveMatch(
                opening = opening,
                eco = opening.eco,
                openingName = opening.name,
                isTabiya = true
            )
        }

        return null
    }

    private fun normalizeBookKey(fen: String): String {
        val parts = fen.trim().split(' ')
        if (parts.isEmpty() || parts[0].isEmpty()) return ""
        val side = parts.getOrNull(1)?.lowercase() ?: return parts[0]
        return "${parts[0]} $side"
    }

    /**
     * Найти дебют по позиции FEN
     */
    fun findOpening(fen: String): OpeningMatch {
        val boardFen = extractBoardFen(fen)
        if (boardFen.isEmpty()) return OpeningMatch.NotFound

        // 1. Точное совпадение расстановки фигур
        fenIndex[boardFen]?.let {
            return OpeningMatch.Found(it, MatchConfidence.EXACT)
        }

        // 2. Структурное совпадение по 64 клеткам доски
        val expandedTarget = expandBoardFen(boardFen) ?: return OpeningMatch.NotFound
        // Стартовую позицию дебютом не считаем
        if (expandedTarget == START_EXPANDED) return OpeningMatch.NotFound

        var bestOpening: OpeningPosition? = null
        var bestScore = 0f

        for (opening in openings) {
            val expandedOpening = expandBoardFen(opening.fenPattern) ?: continue
            // Пешечная структура дебюта должна совпадать достаточно близко
            val similarity = boardSimilarity(expandedTarget, expandedOpening)
            // При равном сходстве предпочитаем более глубокий вариант
            if (similarity > bestScore ||
                (similarity == bestScore && bestOpening != null && opening.moveCount > bestOpening.moveCount)
            ) {
                bestScore = similarity
                bestOpening = opening
            }
        }

        if (bestOpening != null && bestScore >= 0.92f) {
            val confidence = if (bestScore >= 0.96f) MatchConfidence.HIGH else MatchConfidence.MEDIUM
            return OpeningMatch.Found(bestOpening, confidence)
        }

        return OpeningMatch.NotFound
    }

    /**
     * Проверка наличия характеристики в дебюте
     */
    fun hasCharacteristic(fen: String, characteristic: OpeningCharacteristic): Boolean {
        return when (val match = findOpening(fen)) {
            is OpeningMatch.Found ->
                match.opening.characteristics.contains(characteristic)
            else -> false
        }
    }

    /**
     * Получить название дебюта
     */
    fun getOpeningName(fen: String): String? {
        return when (val match = findOpening(fen)) {
            is OpeningMatch.Found -> match.opening.name
            else -> null
        }
    }

    /**
     * Получить контекст для позиционного анализа
     */
    fun getOpeningContext(fen: String, moveNumber: Int): OpeningContext {
        val match = findOpening(fen)

        if (match is OpeningMatch.Found) {
            val opening = match.opening

            return OpeningContext(
                isKnownOpening = true,
                openingName = opening.name,
                allowsEarlyQueen = opening.characteristics.contains(
                    OpeningCharacteristic.EARLY_QUEEN_DEVELOPMENT
                ),
                allowsFlankPlay = opening.characteristics.any {
                    it in setOf(
                        OpeningCharacteristic.WING_ATTACK,
                        OpeningCharacteristic.HYPERMODERN,
                        OpeningCharacteristic.FIANCHETTO
                    )
                },
                expectedCenterStrategy = determineCenterStrategy(opening.characteristics),
                phase = determinePhase(moveNumber)
            )
        }

        // Неизвестный дебют
        return OpeningContext(
            isKnownOpening = false,
            openingName = null,
            allowsEarlyQueen = false,
            allowsFlankPlay = false,
            expectedCenterStrategy = CenterStrategy.OCCUPY_WITH_PAWNS,
            phase = determinePhase(moveNumber)
        )
    }

    /**
     * Найти дебют по ECO коду
     */
    fun findByEco(eco: String): List<OpeningPosition> {
        return ecoIndex[eco] ?: emptyList()
    }

    /**
     * Человекочитаемое русское название дебютного семейства по ECO-коду (A00–E99),
     * чтобы не показывать пользователю голый шифр вроде «ECO A40».
     */
    fun ecoFallbackName(eco: String?): String? {
        val code = eco?.trim()?.uppercase(Locale.ROOT)
            ?.takeIf { it.length == 3 && it[0] in 'A'..'E' && it[1].isDigit() && it[2].isDigit() }
            ?: return null
        return when (code[0]) {
            'A' -> when (code) {
                "A00" -> "Нестандартное начало"
                "A01" -> "Дебют Ларсена"
                "A02", "A03" -> "Дебют Берда"
                in "A04".."A09" -> "Дебют Рети"
                in "A10".."A39" -> "Английское начало"
                in "A40".."A44" -> "Дебют ферзевых пешек"
                in "A45".."A49" -> "Индийская система"
                in "A50".."A55" -> "Староиндийское начало"
                in "A56".."A79" -> "Защита Бенони"
                in "A80".."A99" -> "Голландская защита"
                else -> "Фланговый дебют"
            }
            'B' -> when (code) {
                "B00" -> "Полуоткрытый дебют (1.e4)"
                "B01" -> "Скандинавская защита"
                in "B02".."B05" -> "Защита Алехина"
                "B06" -> "Современная защита"
                in "B07".."B09" -> "Защита Пирца-Уфимцева"
                in "B10".."B19" -> "Защита Каро-Канн"
                in "B20".."B99" -> "Сицилианская защита"
                else -> "Полуоткрытый дебют"
            }
            'C' -> when (code) {
                in "C00".."C19" -> "Французская защита"
                in "C20".."C24" -> "Открытый дебют (1.e4 e5)"
                in "C25".."C29" -> "Венская партия"
                in "C30".."C39" -> "Королевский гамбит"
                in "C40".."C41" -> "Защита Филидора"
                in "C42".."C43" -> "Русская партия"
                in "C44".."C45" -> "Шотландская партия"
                in "C46".."C49" -> "Дебют четырёх коней"
                in "C50".."C59" -> "Итальянская партия"
                in "C60".."C99" -> "Испанская партия"
                else -> "Открытый дебют"
            }
            'D' -> when (code) {
                in "D00".."D05" -> "Дебют ферзевых пешек"
                in "D06".."D09" -> "Ферзевый гамбит"
                in "D10".."D19" -> "Славянская защита"
                in "D20".."D29" -> "Принятый ферзевый гамбит"
                in "D30".."D69" -> "Отказанный ферзевый гамбит"
                in "D70".."D99" -> "Защита Грюнфельда"
                else -> "Дебют ферзевых пешек"
            }
            'E' -> when (code) {
                in "E00".."E09" -> "Каталонское начало"
                "E10", "E11" -> "Индийская защита"
                in "E12".."E19" -> "Новоиндийская защита"
                in "E20".."E59" -> "Защита Нимцовича"
                in "E60".."E99" -> "Староиндийская защита"
                else -> "Индийская защита"
            }
            else -> null
        }
    }

    private val ECO_URL_MOVE_TAIL = Regex("""(?:^|[\s\-])\d+\.{1,3}[A-Za-z0-9+=#\-].*$""")
    private val NON_ALNUM_EN = Regex("""['’`\-\s]+""")
    private val MULTI_SPACE = Regex("""\s+""")
    private val CYRILLIC_CHECK = Regex("""[А-Яа-яЁё]""")

    // Ключи без апострофов и дефисов, от длинных к коротким
    private val EN_FAMILY_TO_RU: List<Pair<String, String>> = listOf(
        "queens gambit declined" to "Отказанный ферзевый гамбит",
        "queens gambit accepted" to "Принятый ферзевый гамбит",
        "kings indian defense" to "Староиндийская защита",
        "kings indian attack" to "Староиндийское начало",
        "queens indian defense" to "Новоиндийская защита",
        "nimzo indian defense" to "Защита Нимцовича",
        "bogo indian defense" to "Защита Боголюбова",
        "old indian defense" to "Староиндийское начало",
        "semi slav defense" to "Полуславянская защита",
        "blackmar diemer gambit" to "Гамбит Блэкмара-Димера",
        "richter veresov attack" to "Дебют Вересова",
        "kings fianchetto opening" to "Дебют Бенко",
        "nimzo larsen attack" to "Дебют Ларсена",
        "queens pawn opening" to "Дебют ферзевых пешек",
        "queens pawn game" to "Дебют ферзевых пешек",
        "kings pawn opening" to "Открытый дебют",
        "kings pawn game" to "Открытый дебют",
        "kings knight opening" to "Дебют королевского коня",
        "three knights opening" to "Дебют трёх коней",
        "four knights game" to "Дебют четырёх коней",
        "two knights defense" to "Защита двух коней",
        "caro kann defense" to "Защита Каро-Канн",
        "scandinavian defense" to "Скандинавская защита",
        "alekhines defense" to "Защита Алехина",
        "nimzowitsch defense" to "Дебют Нимцовича",
        "sicilian defense" to "Сицилианская защита",
        "french defense" to "Французская защита",
        "italian game" to "Итальянская партия",
        "ruy lopez" to "Испанская партия",
        "spanish game" to "Испанская партия",
        "scotch game" to "Шотландская партия",
        "petrovs defense" to "Русская партия",
        "petroff defense" to "Русская партия",
        "philidor defense" to "Защита Филидора",
        "vienna game" to "Венская партия",
        "bishops opening" to "Дебют слона",
        "kings gambit" to "Королевский гамбит",
        "danish gambit" to "Датский гамбит",
        "center game" to "Дебют центра",
        "ponziani opening" to "Дебют Понциани",
        "evans gambit" to "Гамбит Эванса",
        "pirc defense" to "Защита Пирца-Уфимцева",
        "modern defense" to "Современная защита",
        "queens gambit" to "Ферзевый гамбит",
        "slav defense" to "Славянская защита",
        "london system" to "Лондонская система",
        "colle system" to "Система Колле",
        "torre attack" to "Атака Торре",
        "trompowsky attack" to "Атака Тромповского",
        "englund gambit" to "Гамбит Энглунда",
        "grunfeld defense" to "Защита Грюнфельда",
        "catalan opening" to "Каталонское начало",
        "benoni defense" to "Защита Бенони",
        "benko gambit" to "Волжский гамбит",
        "budapest gambit" to "Будапештский гамбит",
        "budapest defense" to "Будапештский гамбит",
        "dutch defense" to "Голландская защита",
        "english opening" to "Английское начало",
        "english defense" to "Английская защита",
        "indian defense" to "Индийская защита",
        "indian game" to "Индийская защита",
        "reti opening" to "Дебют Рети",
        "birds opening" to "Дебют Берда",
        "larsens opening" to "Дебют Ларсена",
        "polish opening" to "Дебют Сокольского"
    )

    private val EN_VARIATION_TO_RU: Map<String, String> = mapOf(
        "horwitz defense" to "защита Горвица",
        "english defense" to "английская защита",
        "mikenas defense" to "вариант Микенаса",
        "wade defense" to "вариант Уэда",
        "englund gambit" to "гамбит Энглунда",
        "modern defense" to "современная защита",
        "old benoni" to "старо-бенический вариант",
        "advance variation" to "вариант продвижения",
        "exchange variation" to "разменный вариант",
        "classical variation" to "классический вариант",
        "closed variation" to "закрытый вариант",
        "closed" to "закрытый вариант",
        "open variation" to "открытый вариант",
        "modern variation" to "современный вариант",
        "modern variations" to "современный вариант",
        "symmetrical variation" to "симметричный вариант",
        "najdorf variation" to "вариант Найдорфа",
        "dragon variation" to "вариант дракона",
        "accelerated dragon" to "ускоренный дракон",
        "alapin variation" to "вариант Алапина",
        "smith morra gambit" to "гамбит Смита-Морра",
        "rossolimo variation" to "вариант Россолимо",
        "taimanov variation" to "вариант Тайманова",
        "sveshnikov variation" to "вариант Свешникова",
        "morphy defense" to "защита Морфи",
        "berlin defense" to "берлинская защита",
        "winawer variation" to "вариант Винавера",
        "tarrasch variation" to "вариант Тарраша",
        "chigorin variation" to "вариант Чигорина",
        "steinitz variation" to "вариант Стейница",
        "panov attack" to "атака Панова",
        "panov botvinnik attack" to "атака Панова-Ботвинника",
        "giuoco piano" to "итальянская партия",
        "agincourt defense" to "вариант e6",
        "anglo indian defense" to "индийский вариант",
        "kings english variation" to "перевёрнутая сицилианская",
        "quiet variation" to "спокойный вариант",
        "normal variation" to "основной вариант",
        "main line" to "главный вариант"
    )

    private fun normalizeEnglishKey(raw: String): String =
        raw.lowercase(Locale.ROOT)
            .replace('é', 'e').replace('ü', 'u')
            .replace(NON_ALNUM_EN, " ")
            .trim()
            .replace(MULTI_SPACE, " ")

    /**
     * Извлекает название дебюта из тега [ECOUrl] (например Chess.com `/openings/...`).
     */
    fun parseEcoUrlOpeningName(ecoUrl: String?): String? {
        val raw = ecoUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val afterOpenings = raw.substringAfter("/openings/", missingDelimiterValue = "")
            .ifEmpty { return null }
            .substringBefore('?')
            .substringBefore('#')
            .trim('/')
        if (afterOpenings.isEmpty()) return null
        val decoded = runCatching { URLDecoder.decode(afterOpenings, StandardCharsets.UTF_8.name()) }
            .getOrDefault(afterOpenings)
        val withoutMoves = decoded.replace(ECO_URL_MOVE_TAIL, "").trim('-', '_', ' ')
        if (withoutMoves.isEmpty()) return null
        val spaced = withoutMoves.replace('-', ' ').replace('_', ' ').replace(MULTI_SPACE, " ").trim()
        if (spaced.isEmpty()) return null
        return translateOpeningName(spaced)
    }

    /**
     * Локализует английское название дебюта (из PGN `[Opening]` или `[ECOUrl]`) на русский язык.
     * Русские названия возвращает без изменений.
     */
    fun translateOpeningName(rawName: String?): String? {
        val trimmed = rawName?.trim()?.replace(MULTI_SPACE, " ")?.takeIf { it.isNotEmpty() } ?: return null
        if (CYRILLIC_CHECK.containsMatchIn(trimmed)) return trimmed

        // Если уже есть двоеточие — переводим отдельно семейство и вариант
        if (':' in trimmed) {
            val leftRaw = trimmed.substringBefore(':').trim()
            val rightRaw = trimmed.substringAfter(':').trim()
            val leftRu = translateFamilyOnly(leftRaw) ?: leftRaw
            val rightRu = translateVariationOnly(rightRaw) ?: rightRaw
            return if (rightRu.isEmpty() || rightRu.equals(leftRu, ignoreCase = true)) {
                leftRu
            } else {
                "$leftRu: $rightRu"
            }
        }

        val norm = normalizeEnglishKey(trimmed)
        for ((key, ru) in EN_FAMILY_TO_RU) {
            if (norm == key) return ru
            if (norm.startsWith("$key ")) {
                val tailRaw = norm.removePrefix("$key ").trim()
                val tailRu = translateVariationOnly(tailRaw) ?: tailRaw
                return if (tailRu.isEmpty()) ru else "$ru: $tailRu"
            }
        }
        return trimmed
    }

    private fun translateFamilyOnly(raw: String): String? {
        val norm = normalizeEnglishKey(raw)
        return EN_FAMILY_TO_RU.firstOrNull { it.first == norm }?.second
    }

    private fun translateVariationOnly(raw: String): String? {
        val norm = normalizeEnglishKey(raw)
        EN_VARIATION_TO_RU[norm]?.let { return it }
        // Пробуем также обрезать суффикс "variation" / "defense" / "system" не найденный дословно
        return EN_FAMILY_TO_RU.firstOrNull { it.first == norm }?.second?.lowercase(Locale.ROOT)
    }

    /**
     * Получить все дебюты с определённой характеристикой
     */
    fun findByCharacteristic(characteristic: OpeningCharacteristic): List<OpeningPosition> {
        return openings.filter { it.characteristics.contains(characteristic) }
    }

    // ================================================================
    // ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ
    // ================================================================

    private const val START_EXPANDED =
        "rnbqkbnrpppppppp................................PPPPPPPPRNBQKBNR"

    private fun extractBoardFen(fen: String): String =
        fen.trim().substringBefore(' ')

    /**
     * Разворачивает 1-е поле FEN в строку ровно из 64 символов ('.' = пустая клетка).
     */
    private fun expandBoardFen(fen: String): String? {
        val boardPart = extractBoardFen(fen)
        val out = StringBuilder(64)
        for (ch in boardPart) {
            when {
                ch == '/' -> continue
                ch in '1'..'8' -> repeat(ch - '0') { out.append('.') }
                else -> out.append(ch)
            }
        }
        return if (out.length == 64) out.toString() else null
    }

    /**
     * Сходство двух развёрнутых 64-клеточных позиций с проверкой дебютных ходов.
     */
    private fun boardSimilarity(target64: String, opening64: String): Float {
        // Все отличия дебютного шаблона от стартовой позиции должны присутствовать в target64
        var keySquares = 0
        var keyMatches = 0
        var totalMatches = 0

        for (i in 0 until 64) {
            if (target64[i] == opening64[i]) {
                totalMatches++
            }
            if (opening64[i] != START_EXPANDED[i]) {
                keySquares++
                if (target64[i] == opening64[i]) {
                    keyMatches++
                }
            }
        }

        if (keySquares == 0 || keyMatches < keySquares) {
            return 0f
        }
        return totalMatches / 64f
    }

    /**
     * Определить стратегию центра по характеристикам
     */
    private fun determineCenterStrategy(
        characteristics: Set<OpeningCharacteristic>
    ): CenterStrategy {
        return when {
            characteristics.contains(OpeningCharacteristic.HYPERMODERN) ->
                CenterStrategy.CONTROL_WITH_PIECES

            characteristics.contains(OpeningCharacteristic.CLASSICAL_CENTER) ->
                CenterStrategy.OCCUPY_WITH_PAWNS

            characteristics.contains(OpeningCharacteristic.WING_ATTACK) ||
                    characteristics.contains(OpeningCharacteristic.FLEXIBLE) ->
                CenterStrategy.FLEXIBLE

            else -> CenterStrategy.MIXED
        }
    }

    /**
     * Определить фазу дебюта по номеру хода
     */
    private fun determinePhase(moveNumber: Int): OpeningPhase {
        return when {
            moveNumber <= 5 -> OpeningPhase.EARLY_OPENING
            moveNumber <= 10 -> OpeningPhase.MID_OPENING
            moveNumber <= 15 -> OpeningPhase.LATE_OPENING
            else -> OpeningPhase.TRANSITION
        }
    }
}