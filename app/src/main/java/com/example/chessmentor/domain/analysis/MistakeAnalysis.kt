// domain/analysis/MistakeAnalysis.kt
package com.example.chessmentor.domain.analysis

import com.github.bhlangonijr.chesslib.PieceType

/**
 * Полный результат семантического анализа ошибки
 */
data class MistakeAnalysis(
    // Основная категория ошибки
    val category: MistakeCategory,

    // Тактический мотив (как соперник наказал / что игрок упустил)
    val tacticalMotif: TacticalMotif?,

    // Информация о потере материала
    val materialLoss: MaterialLoss?,

    // Человекочитаемое объяснение для игрока
    val explanation: String,

    // Информация о лучшем ходе (показываем только для тактических ошибок)
    val betterMoveInfo: BetterMoveInfo?,

    // Линия наказания для анимации (3-6 ходов)
    val punishmentLine: List<String>,

    // Теги для кластеризации похожих позиций
    val patternTags: Set<String> = emptySet()
) {
    /**
     * Нужно ли показывать блок "Лучше было..."?
     * Только для тактических ошибок, где разница понятна игроку
     */
    fun shouldShowBetterMove(): Boolean {
        return betterMoveInfo != null && category != MistakeCategory.POSITIONAL
    }
}

/**
 * Категории ошибок
 */
enum class MistakeCategory(val displayName: String) {
    MATE_THREAT("Угроза мата"),
    MATERIAL_BLUNDER("Потеря материала"),
    MISSED_TACTIC("Упущенная тактика"),
    KING_SAFETY("Безопасность короля"),
    POSITIONAL("Позиционная неточность")
}

/**
 * Тактические мотивы
 */
enum class TacticalMotif(val russianName: String, val description: String) {
    // Двойные удары
    FORK("Вилка", "Одна фигура атакует две цели одновременно"),

    // Связки и сквозные удары
    PIN("Связка", "Фигура не может уйти, так как прикрывает более ценную"),
    SKEWER("Сквозной удар", "Атака сквозь ценную фигуру на менее ценную"),

    // Открытые нападения
    DISCOVERED_ATTACK("Вскрытое нападение", "Уход фигуры открывает атаку другой"),
    DISCOVERED_CHECK("Вскрытый шах", "Уход фигуры открывает шах"),

    // Матовые мотивы
    BACK_RANK("Мат на последней горизонтали", "Король заперт своими фигурами"),
    SMOTHERED_MATE("Спёртый мат", "Король окружён своими фигурами, мат конём"),

    // Атаки на защитников
    OVERLOADING("Перегрузка", "Фигура не справляется с двумя задачами"),
    REMOVING_DEFENDER("Отвлечение защитника", "Защитник вынужден уйти"),
    DEFLECTION("Отвлечение", "Фигуру заставили уйти с важного поля"),

    // Ловля фигур
    TRAPPED_PIECE("Пойманная фигура", "Фигуре некуда отступить"),
    QUEEN_TRAP("Ловля ферзя", "Ферзь попал в ловушку"),

    // Простые зевки
    HANGING_PIECE("Незащищённая фигура", "Фигура осталась без защиты")
}

/**
 * Информация о потере материала
 */
data class MaterialLoss(
    val pieceType: PieceType,
    val pieceValue: Int,
    val wasHanging: Boolean,        // Фигура была без защиты
    val wasTrapped: Boolean,        // Фигура была в ловушке
    val attackersCount: Int,        // Сколько фигур атаковало
    val defendersCount: Int         // Сколько фигур защищало
) {
    /**
     * Название потерянной фигуры на русском
     */
    fun pieceName(): String = when (pieceType) {
        PieceType.QUEEN -> "ферзь"
        PieceType.ROOK -> "ладья"
        PieceType.BISHOP -> "слон"
        PieceType.KNIGHT -> "конь"
        PieceType.PAWN -> "пешка"
        PieceType.KING -> "король"
        else -> "фигура"
    }

    /**
     * Краткое описание потери
     */
    fun shortDescription(): String {
        return when {
            wasHanging -> "${pieceName()} без защиты"
            attackersCount > defendersCount -> "${pieceName()} под атакой ($attackersCount vs $defendersCount)"
            wasTrapped -> "${pieceName()} в ловушке"
            else -> "потеря: ${pieceName()}"
        }
    }
}

/**
 * Информация о лучшем ходе
 */
data class BetterMoveInfo(
    val moveUci: String,                // Ход в формате UCI (e2e4)
    val moveNotation: String?,          // Человекочитаемая нотация (Кf3, опционально)
    val evaluationAfter: Int,           // Оценка после хода в сантипешках
    val explanation: String,            // "Защищало ферзя", "Вилка с выигрышем"
    val wasWinning: Boolean             // Был ли это выигрыш (не только защита)
) {
    /**
     * Форматированная оценка для UI
     */
    fun formattedEval(): String {
        val pawns = evaluationAfter / 100.0
        return if (pawns >= 0) {
            String.format(java.util.Locale.US, "+%.1f", pawns)
        } else {
            String.format(java.util.Locale.US, "%.1f", pawns)
        }
    }
}

// ============================================================
// СОХРАНЯЕМ СТАРЫЙ ENUM ДЛЯ ОБРАТНОЙ СОВМЕСТИМОСТИ
// (постепенно уберём после миграции)
// ============================================================

/**
 * @deprecated Используйте MistakeAnalysis вместо MistakeReason
 */
enum class MistakeReason(val message: String) {
    MATE_FORCED("Допущен мат!"),
    MATERIAL_BLUNDER("Зевок материала!"),
    KING_EXPOSED("Король под атакой!"),
    POSITIONAL("Неточность");

    /**
     * Конвертация в новый формат
     */
    fun toAnalysis(): MistakeAnalysis {
        return MistakeAnalysis(
            category = when (this) {
                MATE_FORCED -> MistakeCategory.MATE_THREAT
                MATERIAL_BLUNDER -> MistakeCategory.MATERIAL_BLUNDER
                KING_EXPOSED -> MistakeCategory.KING_SAFETY
                POSITIONAL -> MistakeCategory.POSITIONAL
            },
            tacticalMotif = null,
            materialLoss = null,
            explanation = message,
            betterMoveInfo = null,
            punishmentLine = emptyList()
        )
    }
}