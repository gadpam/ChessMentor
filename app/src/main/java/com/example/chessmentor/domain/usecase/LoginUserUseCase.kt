package com.example.chessmentor.domain.usecase

import com.example.chessmentor.domain.entity.User
import com.example.chessmentor.domain.repository.UserRepository
import com.example.chessmentor.domain.security.PasswordHasher

/**
 * Use Case: Вход пользователя в систему
 *
 * Бизнес-логика авторизации:
 * 1. Поиск пользователя по email
 * 2. Проверка пароля
 * 3. Обновление времени последнего входа
 */
class LoginUserUseCase(
    private  val userRepository: UserRepository
) {

    /**
     * Входные данные для входа
     */
    data class Input(
        val email: String,
        val password: String
    )

    /**
     * Результат входа
     */
    sealed class Result {
        data class Success(val user: User) : Result()
        data class Error(val message: String) : Result()
    }

    /**
     * Выполнить вход
     */
    suspend fun execute(input: Input): Result {
        // Валидация входных данных
        if (input.email.isBlank()) {
            return Result.Error("Email не может быть пустым")
        }

        if (input.password.isBlank()) {
            return Result.Error("Пароль не может быть пустым")
        }

        // Поиск пользователя по email
        val user = userRepository.findByEmail(input.email)
            ?: return Result.Error("Пользователь с таким email не найден")

        // Проверка пароля: новый формат PBKDF2 или старый "hashed_" (для миграции)
        val legacy = PasswordHasher.isLegacyHash(user.passwordHash)
        val passwordOk = if (legacy) {
            PasswordHasher.verifyLegacy(input.password, user.passwordHash)
        } else {
            PasswordHasher.verify(input.password, user.passwordHash)
        }
        if (!passwordOk) {
            return Result.Error("Неверный пароль")
        }

        // Старые записи переписываем в новом формате
        val rehashedUser = if (legacy) {
            user.withPasswordHash(PasswordHasher.hash(input.password))
        } else {
            user
        }

        // Обновление времени последнего входа
        val updatedUser = rehashedUser.withLogin()
        userRepository.update(updatedUser)

        return Result.Success(updatedUser)
    }
}
