// domain/opening/OpeningDatabase.kt
package com.example.chessmentor.domain.opening

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.move.MoveList

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
            fenPattern = "rnbqk2r/pppp1ppp/4pn2/8/1bPP4/8/PP2PPPP/RNBQKBNR",
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
        CanonicalBookLine("C57", "Защита двух коней", listOf("e4", "e5", "Nf3", "Nc6", "Bc4", "Nf6", "Ng5", "d5", "exd5", "Na5")),
        CanonicalBookLine("C55", "Защита двух коней (спокойная)", listOf("e4", "e5", "Nf3", "Nc6", "Bc4", "Nf6", "d3", "Be7", "O-O", "O-O")),
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
        CanonicalBookLine("B01", "Скандинавская защита", listOf("e4", "d5", "exd5", "Qxd5", "Nc3", "Qa5", "d4", "Nf6", "Nf3", "Bf5")),
        CanonicalBookLine("B07", "Защита Пирца-Уфимцева", listOf("e4", "d6", "d4", "Nf6", "Nc3", "g6", "Nf3", "Bg7", "Be2", "O-O")),
        CanonicalBookLine("B02", "Защита Алехина", listOf("e4", "Nf6", "e5", "Nd5", "d4", "d6", "Nf3")),
        // Закрытые и полузакрытые дебюты
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
        if (opening.moves.isEmpty()) return
        runCatching {
            val moveList = MoveList()
            for (san in opening.moves) {
                moveList.addSanMove(san, false, true)
            }
            val board = Board()
            for ((idx, move) in moveList.withIndex()) {
                if (!board.doMove(move)) break
                val key = normalizeBookKey(board.fen)
                if (idx == moveList.lastIndex) {
                    val prev = exactBookOpenings[key]
                    if (prev == null || opening.moveCount >= prev.moveCount) {
                        exactBookOpenings[key] = opening
                    }
                } else {
                    bookPrefixPositions.add(key)
                }
            }
        }
    }

    private fun indexCanonicalLine(line: CanonicalBookLine) {
        if (line.moves.isEmpty()) return
        runCatching {
            val moveList = MoveList()
            for (san in line.moves) {
                moveList.addSanMove(san, false, true)
            }
            val board = Board()
            for ((idx, move) in moveList.withIndex()) {
                if (!board.doMove(move)) break
                val key = normalizeBookKey(board.fen)
                if (idx == moveList.lastIndex) {
                    canonicalLineOpenings.putIfAbsent(key, line.eco to line.name)
                } else {
                    bookPrefixPositions.add(key)
                }
            }
        }
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