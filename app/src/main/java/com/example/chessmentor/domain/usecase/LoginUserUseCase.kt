package com.example.chessmentor.domain.usecase

import com.example.chessmentor.domain.entity.User
import com.example.chessmentor.domain.repository.UserRepository
import com.example.chessmentor.domain.security.PasswordHasher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Use Case: Вход пользователя в систему
 */
class LoginUserUseCase(
    private val userRepository: UserRepository
) {

    data class Input(
        val email: String,
        val password: String
    )

    sealed class Result {
        data class Success(val user: User) : Result()
        data class Error(val message: String) : Result()
    }

    suspend fun execute(input: Input): Result {
        val normalizedEmail = input.email.trim()
        if (normalizedEmail.isBlank()) {
            return Result.Error("Email не может быть пустым")
        }

        if (input.password.isBlank()) {
            return Result.Error("Пароль не может быть пустым")
        }

        val user = userRepository.findByEmail(normalizedEmail)
            ?: return Result.Error("Пользователь с таким email не найден")

        // PBKDF2 (310 000 итераций) выполняем вне главного потока, чтобы не блокировать UI
        val legacy = PasswordHasher.isLegacyHash(user.passwordHash)
        val passwordOk = withContext(Dispatchers.Default) {
            if (legacy) {
                PasswordHasher.verifyLegacy(input.password, user.passwordHash)
            } else {
                PasswordHasher.verify(input.password, user.passwordHash)
            }
        }
        if (!passwordOk) {
            return Result.Error("Неверный пароль")
        }

        val rehashedUser = if (legacy) {
            val upgradedHash = withContext(Dispatchers.Default) {
                PasswordHasher.hash(input.password)
            }
            user.withPasswordHash(upgradedHash)
        } else {
            user
        }

        val updatedUser = rehashedUser.withLogin()
        userRepository.update(updatedUser)

        return Result.Success(updatedUser)
    }
}
