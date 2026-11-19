// domain/usecase/ChangePasswordUseCase.kt
package com.example.chessmentor.domain.usecase

import com.example.chessmentor.domain.entity.User
import com.example.chessmentor.domain.repository.UserRepository
import com.example.chessmentor.domain.security.PasswordHasher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Use Case: Безопасная смена пароля пользователя с проверкой текущего пароля
 * и перехешированием в PBKDF2-HMAC-SHA256 (310 000 итераций).
 */
class ChangePasswordUseCase(
    private val userRepository: UserRepository
) {
    companion object {
        const val MIN_PASSWORD_LENGTH = 6
        const val MAX_PASSWORD_LENGTH = 128
    }

    data class Input(
        val userId: Long,
        val currentPassword: String,
        val newPassword: String,
        val confirmPassword: String
    )

    sealed class Result {
        data class Success(val user: User) : Result()
        data class Error(val message: String) : Result()
    }

    suspend fun execute(input: Input): Result {
        if (input.currentPassword.isEmpty()) {
            return Result.Error("Введите текущий пароль")
        }
        if (input.newPassword.length < MIN_PASSWORD_LENGTH) {
            return Result.Error("Новый пароль должен содержать не менее $MIN_PASSWORD_LENGTH символов")
        }
        if (input.newPassword.length > MAX_PASSWORD_LENGTH) {
            return Result.Error("Новый пароль слишком длинный (максимум $MAX_PASSWORD_LENGTH символов)")
        }
        if (input.newPassword != input.confirmPassword) {
            return Result.Error("Новый пароль и подтверждение не совпадают")
        }
        if (input.newPassword == input.currentPassword) {
            return Result.Error("Новый пароль должен отличаться от текущего")
        }

        val user = userRepository.findById(input.userId)
            ?: return Result.Error("Пользователь не найден")

        val isCurrentValid = withContext(Dispatchers.Default) {
            if (PasswordHasher.isLegacyHash(user.passwordHash)) {
                PasswordHasher.verifyLegacy(input.currentPassword, user.passwordHash)
            } else {
                PasswordHasher.verify(input.currentPassword, user.passwordHash)
            }
        }
        if (!isCurrentValid) {
            return Result.Error("Текущий пароль указан неверно")
        }

        val newHash = withContext(Dispatchers.Default) {
            PasswordHasher.hash(input.newPassword)
        }

        val updated = user.withPasswordHash(newHash)
        userRepository.update(updated)
        return Result.Success(updated)
    }
}
