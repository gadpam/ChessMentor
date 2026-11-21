// data/local/RoomTransactionRunner.kt
package com.example.chessmentor.data.local

import androidx.room.withTransaction
import com.example.chessmentor.domain.repository.TransactionRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Транзакция Room поверх [TransactionRunner].
 *
 * Диспетчер фиксирован намеренно. Room 2.6 запоминает корутинный контекст, в котором
 * открыта транзакция, и при обращении к БД из другого контекста бросает
 * `IllegalStateException` («Cannot access database on a different coroutine context
 * inherited from a suspending transaction»). Репозитории проекта оборачивают вызовы DAO
 * в `withContext(Dispatchers.IO)`, поэтому транзакция открывается на том же
 * `Dispatchers.IO` — независимо от диспетчера вызывающего кода.
 *
 * Если [block] бросает исключение, изменения откатываются целиком: прерванное
 * восстановление копии не оставляет партии без разбора, задачи без попыток или
 * обновлённый профиль без данных.
 */
class RoomTransactionRunner(private val database: AppDatabase) : TransactionRunner {

    override suspend fun <T> run(block: suspend () -> T): T =
        withContext(Dispatchers.IO) {
            database.withTransaction { block() }
        }
}
