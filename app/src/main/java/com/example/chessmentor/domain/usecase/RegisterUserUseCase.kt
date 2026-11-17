package com.example.chessmentor.domain.usecase

import com.example.chessmentor.domain.entity.User
import com.example.chessmentor.domain.repository.UserRepository
import com.example.chessmentor.domain.security.PasswordHasher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Use Case: Регистрация нового пользователя
 */
class RegisterUserUseCase(
    private val userRepository: UserRepository
) {
    companion object {
        private val EMAIL_REGEX = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    }

    data class Input(
        val nickname: String,
        val email: String,
        val password: String,
        val rating: Int = 1200
    )

    sealed class Result {
        data class Success(val user: User) : Result()
        data class Error(val message: String) : Result()
    }

    suspend fun execute(input: Input): Result {
        val nickname = input.nickname.trim()
        val email = input.email.trim()

        if (nickname.length < 3) {
            return Result.Error("Никнейм должен быть не менее 3 символов")
        }
        if (nickname.length > 50) {
            return Result.Error("Никнейм должен быть не более 50 символов")
        }

        if (!EMAIL_REGEX.matches(email)) {
            return Result.Error("Некорректный email")
        }

        if (input.password.length < 6) {
            return Result.Error("Пароль должен быть не менее 6 символов")
        }

        if (input.rating !in 0..3500) {
            return Result.Error("Рейтинг должен быть от 0 до 3500")
        }

        if (userRepository.existsByEmail(email)) {
            return Result.Error("Пользователь с таким email уже существует")
        }

        if (userRepository.existsByNickname(nickname)) {
            return Result.Error("Пользователь с таким никнеймом уже существует")
        }

        // PBKDF2-HMAC-SHA256 выполняем на фоновом потоке
        val passwordHash = withContext(Dispatchers.Default) {
            PasswordHasher.hash(input.password)
        }

        val newUser = User(
            nickname = nickname,
            email = email,
            passwordHash = passwordHash,
            rating = input.rating
        )

        val savedUser = userRepository.save(newUser)
        return Result.Success(savedUser)
    }
}
