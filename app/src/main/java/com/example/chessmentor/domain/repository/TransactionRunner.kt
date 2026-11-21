// domain/repository/TransactionRunner.kt
package com.example.chessmentor.domain.repository

/**
 * Граница транзакции, не зависящая от конкретной БД.
 *
 * Домен не знает про Room: use case получает [TransactionRunner] и выполняет внутри
 * [run] обычные операции репозиториев. Реализация ([com.example.chessmentor.data.local.RoomTransactionRunner])
 * открывает одну транзакцию и откатывает её целиком, если [run] бросил исключение.
 *
 * Без реализации (`null`) use case работает как раньше: каждая запись фиксируется
 * отдельно. Это нужно для тестов с in-memory фейками.
 */
interface TransactionRunner {
    suspend fun <T> run(block: suspend () -> T): T
}
