# Карта кода, данных и сборки

[К документации проекта](../README.md) · [Проверки на GitHub и телефоне](testing.md) · [Изменения уровня 1: целостность данных и разбор](integrity-and-analysis-fixes.md)

## От экрана до хранилища

Один Android-модуль `:app`. [MainActivity](../app/src/main/java/com/example/chessmentor/MainActivity.kt) получает `AppContainer`, восстанавливает сессию и запускает инициализацию Stockfish. [ChessMentorNavigation](../app/src/main/java/com/example/chessmentor/presentation/ui/components/navigation/ChessMentorNavigation.kt) выбирает экран, передаёт обработчики и определяет, куда вернётся пользователь. Менять маршрут только в дочерней карточке недостаточно: проверить ещё системное «Назад» и стрелку верхней панели.

| Задача | Откуда начать читать код | Где проверить результат |
|---|---|---|
| Изменить кнопку или сообщение | [Compose-экраны](../app/src/main/java/com/example/chessmentor/presentation/ui/components/screen), [общие компоненты](../app/src/main/java/com/example/chessmentor/presentation/ui/components) | [Инвентарь с именами компонентов](android-ui-inventory.md), Compose-тесты |
| Исправить устаревшее превью / выбор не той записи | [GameViewModel](../app/src/main/java/com/example/chessmentor/presentation/viewmodel/GameViewModel.kt): `onPgnInputChanged`, `indexSavedGames`, `importChessComGames`; [парсер PGN](../app/src/main/java/com/example/chessmentor/domain/pgn/PgnParser.kt) | Проверять ID и отпечаток PGN, а не только название соперника. Архив и ручной ввод сходятся в [UploadGameUseCase](../app/src/main/java/com/example/chessmentor/domain/usecase/UploadGameUseCase.kt). |
| Изменить классификацию или навигацию по ходам | [domain/analysis](../app/src/main/java/com/example/chessmentor/domain/analysis), [BoardViewModel](../app/src/main/java/com/example/chessmentor/presentation/viewmodel/BoardViewModel.kt) | Не путать индекс полухода с номером хода в PGN. В графике `evaluations[0]` — стартовая позиция. [AnalyzeGameUseCase](../app/src/main/java/com/example/chessmentor/domain/usecase/AnalyzeGameUseCase.kt) прерывает разбор, если движок не оценил позицию (`EngineAnalysisException`): точность и число ошибок никогда не считаются по подставленным оценкам. Прогресс разбора живёт в [GameViewModel](../app/src/main/java/com/example/chessmentor/presentation/viewmodel/GameViewModel.kt): терминальные состояния снимают признак «движок занят», а разбор, прерванный при пересоздании ViewModel, продолжается после готовности движка. |
| Изменить подбор упражнений или статистику | [domain/training](../app/src/main/java/com/example/chessmentor/domain/training), [domain/statistics](../app/src/main/java/com/example/chessmentor/domain/statistics), [модели экранов](../app/src/main/java/com/example/chessmentor/presentation/viewmodel) | Различать общую задачу, личную ошибку из конкретной партии и попытку пользователя. Не считать отсутствие оценок нулевой точностью. |
| Диагностировать инициализацию и поиск | [data/engine](../app/src/main/java/com/example/chessmentor/data/engine), [JNI и CMake](../app/src/main/cpp) | Движок работает локально. Прогрев при старте идёт в `AppContainer.appScope` и не отменяется поворотом экрана: загрузка NNUE (~100 МБ) не теряется. Повторный `ensureReadyOnStartup()` не перезапускает уже идущую загрузку, пауза пользователя не перетирается, аварийно оборванная инициализация повторяется. Ошибка загрузки сети — не ошибка разбора PGN. |
| Изменить запись данных | [Контракты репозиториев](../app/src/main/java/com/example/chessmentor/domain/repository), [реализации](../app/src/main/java/com/example/chessmentor/data/repository), [AppContainer](../app/src/main/java/com/example/chessmentor/di/AppContainer.kt) | Новый источник данных подключать через существующие операции импорта, не создавать параллельную библиотеку. Не оставлять заглушек, возвращающих пустой список: вызывающий код принимает это за «данных нет». Многошаговые записи выполнять через [TransactionRunner](../app/src/main/java/com/example/chessmentor/domain/repository/TransactionRunner.kt). |

## Что сохраняется и переносится

- **Room v5:** `users` — локальные профили; `games` — PGN, сторона, статус и метрики; `analyzed_moves` и `mistakes` — разбор; `exercises` и `exercise_attempts` — задачи и попытки. [Определение БД и миграции](../app/src/main/java/com/example/chessmentor/data/local) и [экспортированные схемы](../app/schemas) — источник истины при изменении формата. Не путать локальный номер партии с ID Chess.com.
- **Настройки и сессия:** `SharedPreferences` хранит активный ID профиля, последний email и настройки; ник источника Chess.com записан отдельно для каждого локального `userId`, не заменяет подсказку ника ручного PGN. «Забыть ник» не удаляет игры. Пароли не нужно передавать сервису архива.
- **JSON и PGN:** [экспорт JSON](../app/src/main/java/com/example/chessmentor/domain/usecase/ExportUserBackupUseCase.kt) собирает настройки профиля, партии с разбором, личные упражнения и попытки. [Восстановление](../app/src/main/java/com/example/chessmentor/domain/usecase/ImportUserBackupUseCase.kt) сопоставляет записи с данными текущего пользователя и применяет их одной транзакцией ([RoomTransactionRunner](../app/src/main/java/com/example/chessmentor/data/local/RoomTransactionRunner.kt), контракт — [TransactionRunner](../app/src/main/java/com/example/chessmentor/domain/repository/TransactionRunner.kt)): при сбое посередине изменения откатываются целиком; это не замена файла БД и не вход в прежнюю учётную запись. Пароль, активная сессия, HTTP-кеш и отдельный ник источника Chess.com не входят в JSON. Такой файл содержит историю игр и тренировок: не прикладывать полную копию к публичному issue. [Экспорт PGN](../app/src/main/java/com/example/chessmentor/domain/pgn/PgnArchiveExporter.kt) нужен для обмена самими партиями.

## Сборка из исходников (не обязательна для тестировщика)

Использовать JDK 17, Gradle Wrapper из репозитория, Android SDK Platform 34 / Build Tools 34.0.0, NDK `26.1.10909125` и CMake `3.22.1`. Минимальный API 29, ABI `arm64-v8a` и `x86_64`. При изменении версий сверяться с [Gradle-конфигурацией модуля](../app/build.gradle.kts) и [Android CI](../.github/workflows/android-ci.yml). Из корня проекта:

```sh
bash gradlew :app:assembleDebug
bash gradlew :app:testDebugUnitTest :app:lintDebug
# Нужен подключённый Android или запущенный эмулятор поддерживаемой архитектуры:
bash gradlew :app:connectedDebugAndroidTest
```

В Windows вместо `bash gradlew` использовать `gradlew.bat`. Устанавливаемый файл локальной debug-сборки — `app/build/outputs/apk/debug/app-debug.apk`. Для релиза не использовать одноразовую подпись CI; [процесс публикации](android-release.md) отделён от сборки и тестов.

## Как поддерживать документацию вместе с кодом

При изменении пользовательского действия обновить строку его компонента в [карте интерфейса](android-ui-inventory.md): откуда вход, когда кнопка доступна, что меняется, что происходит при ошибке/отмене, куда ведёт возврат. Для сетевого источника также описать лимиты и дубликаты. Указать тест или пометку «проверить вручную»; план не описывать как реализованное поведение. Исторический зелёный запуск относится к конкретному SHA, а не ко всем будущим версиям.
