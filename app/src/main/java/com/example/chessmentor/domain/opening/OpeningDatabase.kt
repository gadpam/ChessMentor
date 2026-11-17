// domain/opening/OpeningDatabase.kt
package com.example.chessmentor.domain.opening

import android.util.Log

/**
 * База знаний о шахматных дебютах
 * Содержит ~30 наиболее популярных дебютов
 */
object OpeningDatabase {

    private const val TAG = "OpeningDB"

    // Кэш для быстрого поиска (ключ — расстановка фигур, 1-е поле FEN)
    private val fenIndex = mutableMapOf<String, OpeningPosition>()
    private val ecoIndex = mutableMapOf<String, MutableList<OpeningPosition>>()

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

    init {
        buildIndexes()
    }

    /**
     * Построение индексов для быстрого поиска
     */
    private fun buildIndexes() {
        openings.forEach { opening ->
            fenIndex[extractBoardFen(opening.fenPattern)] = opening
            ecoIndex.getOrPut(opening.eco) { mutableListOf() }.add(opening)
        }

        Log.d(TAG, "Loaded ${openings.size} openings")
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