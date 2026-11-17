// domain/training/InterleavedPatternQueue.kt
package com.example.chessmentor.domain.training

/**
 * Очередь отложенного чередования тактических мотивов (*Interleaved Practice*).
 *
 * Вместо того чтобы давать 2–3 одинаковые задачи подряд сразу после ошибки
 * (когда игрок механически угадывает ответ, заранее зная мотив), очередь вставляет
 * между ними [FIRST_GAP] и [SECOND_GAP] задач на ДРУГИЕ темы. Когда мотив возвращается
 * неожиданно, тренируется настоящее тактическое распознавание, как в живой партии.
 */
class InterleavedPatternQueue(
    private val firstGap: Int = FIRST_GAP,
    private val secondGap: Int = SECOND_GAP,
    private val maxScheduled: Int = MAX_SCHEDULED
) {
    companion object {
        /** Первый скрытый повтор того же мотива — после 2 задач на другие темы. */
        const val FIRST_GAP = 2

        /** Второй контрольный повтор мотива — после 5 задач. */
        const val SECOND_GAP = 5

        private const val MAX_SCHEDULED = 8
    }

    data class ScheduledSlot(
        val patternName: String,
        var remainingOtherExercises: Int
    )

    private val slots = mutableListOf<ScheduledSlot>()

    /** Последний показанный мотив — используется для чередования, чтобы не давать дубли подряд. */
    var lastServedPattern: String? = null
        private set

    val pendingCount: Int
        get() = slots.size

    /**
     * Планирует отложенное закрепление [patternName] после допущенной ошибки.
     */
    fun scheduleAfterMistake(patternName: String) {
        val clean = patternName.trim()
        if (clean.isEmpty()) return

        // Не дублируем уже запланированный близкий повтор того же мотива
        val existingForPattern = slots.count { it.patternName == clean }
        if (existingForPattern == 0 && slots.size < maxScheduled) {
            slots.add(ScheduledSlot(clean, firstGap))
        }
        if (existingForPattern <= 1 && slots.size < maxScheduled) {
            slots.add(ScheduledSlot(clean, secondGap))
        }
    }

    /**
     * Сообщает очереди о переходе к следующей задаче и, если подошёл срок
     * одного из отложенных мотивов (и он не совпадает с только что сыгранным),
     * возвращает имя мотива для подмешивания в поток.
     */
    fun pollDuePattern(): String? {
        if (slots.isEmpty()) return null

        val readyIndex = slots.indexOfFirst { slot ->
            slot.remainingOtherExercises <= 0 && slot.patternName != lastServedPattern
        }
        if (readyIndex >= 0) {
            return slots.removeAt(readyIndex).patternName
        }

        // Если единственный готовый слот совпал с только что сыгранной темой — отложим ещё на 1 ход
        return null
    }

    /**
     * Фиксирует фактически выданную пользователю задачу с мотивом [servedPatternName],
     * уменьшая счётчики ожидания у остальных запланированных мотивов.
     */
    fun onExerciseServed(servedPatternName: String?) {
        val clean = servedPatternName?.trim()?.takeIf { it.isNotEmpty() }
        lastServedPattern = clean

        for (slot in slots) {
            if (slot.patternName != clean && slot.remainingOtherExercises > 0) {
                slot.remainingOtherExercises--
            }
        }
    }

    fun clear() {
        slots.clear()
        lastServedPattern = null
    }
}
