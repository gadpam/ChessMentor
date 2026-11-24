# Карта кода, данных и сборки

[К документации проекта](../README.md) · [Проверки на GitHub и телефоне](testing.md) · [Изменения уровня 1 и 2: целостность данных и разбор](integrity-and-analysis-fixes.md)

## От экрана до хранилища

Один Android-модуль `:app` (пакет `com.example.chessmentor`). [MainActivity](../app/src/main/java/com/example/chessmentor/MainActivity.kt) получает [AppContainer](../app/src/main/java/com/example/chessmentor/di/AppContainer.kt), применяет тему и запускает фоновый прогрев Stockfish в `container.appScope`. [ChessMentorNavigation](../app/src/main/java/com/example/chessmentor/presentation/ui/components/navigation/ChessMentorNavigation.kt) восстанавливает сессию (`restoreSessionIfNeeded`), выбирает экран, управляет системной кнопкой «Назад» и точками возврата (`summaryReturnScreen`, `uploadReturnScreen`, `trainingReturnScreen`, `settingsReturnScreen`, `engineSettingsReturnScreen`). Менять маршрут только в дочерней карточке недостаточно: проверять также системное «Назад» и кнопку возврата верхней панели.

| Задача | Откуда начать читать код | Что учесть и где проверить |
|---|---|---|
| Изменить кнопку или сообщение | [Compose-экраны](../app/src/main/java/com/example/chessmentor/presentation/ui/components/screen), [общие компоненты](../app/src/main/java/com/example/chessmentor/presentation/ui/components), [темы и палитра](../app/src/main/java/com/example/chessmentor/presentation/ui/components/theme) | Сверять строку компонента с [инвентарём](android-ui-inventory.md) и UI-тестами в `presentation/ui`. Использовать цвета ролей из `LocalChessPalette`. |
| Исправить устаревшее превью, дубликаты или импорт | [GameViewModel](../app/src/main/java/com/example/chessmentor/presentation/viewmodel/GameViewModel.kt) (`onPgnInputChanged`, `importChessComGames`, `deleteGame`, `selectGame`), [парсер PGN](../app/src/main/java/com/example/chessmentor/domain/pgn/PgnParser.kt), [архив Chess.com](../app/src/main/java/com/example/chessmentor/data/chesscom/ChessComArchive.kt) | Проверять ID и канонический отпечаток PGN, а не только имя соперника. Одиночный, пакетный PGN-импорт и Chess.com сходятся в [UploadGameUseCase](../app/src/main/java/com/example/chessmentor/domain/usecase/UploadGameUseCase.kt). |
| Изменить классификацию, анализ или навигацию по ходам | [domain/analysis](../app/src/main/java/com/example/chessmentor/domain/analysis), [OpeningDatabase](../app/src/main/java/com/example/chessmentor/domain/opening/OpeningDatabase.kt), [AnalyzeGameUseCase](../app/src/main/java/com/example/chessmentor/domain/usecase/AnalyzeGameUseCase.kt), [BoardViewModel](../app/src/main/java/com/example/chessmentor/presentation/viewmodel/BoardViewModel.kt) | Не путать `moveIndex` (0-based полуход) с `moveNumber` (1-based полный ход). `evaluations[0]` — стартовая позиция. Отсутствие оценки движка выбрасывает `EngineAnalysisException`: точность и число ошибок никогда не считаются по подставным числам. |
| Изменить подбор задач, подсказки или рейтинг тренировки | [domain/training](../app/src/main/java/com/example/chessmentor/domain/training), [GenerateExercisesFromMistakesUseCase](../app/src/main/java/com/example/chessmentor/domain/usecase/GenerateExercisesFromMistakesUseCase.kt), [GetTrainingExerciseUseCase](../app/src/main/java/com/example/chessmentor/domain/usecase/GetTrainingExerciseUseCase.kt), [TrainingViewModel](../app/src/main/java/com/example/chessmentor/presentation/viewmodel/TrainingViewModel.kt) | Различать общие задачи (`CuratedExerciseBank`), личные задачи (`ExerciseSource.MISTAKE` с `sourceGameId`) и подходы (`SpacedRepetitionScheduler`). Ошибка проверки движка (`checkUnavailable`) не считается ошибкой хода. |
| Изменить статистику игрока и дебютный отчёт | [PlayerAnalyticsCalculator](../app/src/main/java/com/example/chessmentor/domain/statistics/PlayerAnalyticsCalculator.kt), [GetUserStatisticsUseCase](../app/src/main/java/com/example/chessmentor/domain/usecase/GetUserStatisticsUseCase.kt), [StatisticsViewModel](../app/src/main/java/com/example/chessmentor/presentation/viewmodel/StatisticsViewModel.kt) | Не смешивать отчёты разных периодов и не считать отсутствие `accuracy` (`null`) нулевой точностью. Внутренний рейтинг тренировок выводится только в «Тактике» после рейтинговой попытки. |
| Диагностировать Stockfish, NNUE и C++/JNI | [data/engine](../app/src/main/java/com/example/chessmentor/data/engine), [domain/engine](../app/src/main/java/com/example/chessmentor/domain/engine/EngineStatus.kt), [app/src/main/cpp](../app/src/main/cpp) | Прогрев идёт в `AppContainer.appScope` (`SupervisorJob` + `Dispatchers.IO`). Ошибка/пауза сети не перетирается автостартом. Прерванный `stop()` поиск возвращает `false` / `null`. |
| Изменить хранение, миграции, бэкапы и профиль | [data/local](../app/src/main/java/com/example/chessmentor/data/local), [domain/repository](../app/src/main/java/com/example/chessmentor/domain/repository), [data/repository](../app/src/main/java/com/example/chessmentor/data/repository), [domain/backup](../app/src/main/java/com/example/chessmentor/domain/backup), [SettingsViewModel](../app/src/main/java/com/example/chessmentor/presentation/viewmodel/SettingsViewModel.kt) | Многошаговые операции БД выполнять через [TransactionRunner](../app/src/main/java/com/example/chessmentor/domain/repository/TransactionRunner.kt). Сохранять актуальность экспортированной схемы [5.json](../app/schemas/com.example.chessmentor.data.local.AppDatabase/5.json). |

---

## Стек и конфигурация сборки

Версии зафиксированы в [gradle/libs.versions.toml](../gradle/libs.versions.toml) и [app/build.gradle.kts](../app/build.gradle.kts):

- **Инструменты:** AGP `8.2.2`, Kotlin `2.0.0`, KSP `2.0.0-1.0.21`, JDK 17 (`coreLibraryDesugaringEnabled = true` с `desugar_jdk_libs:2.0.4`), Android SDK Platform `34`, Build Tools `34.0.0` (`35.0.0` в `sign-apk.sh`), NDK `26.1.10909125`, CMake `3.22.1`.
- **Android-границы:** `applicationId = "com.example.chessmentor"`, `minSdk = 29` (Android 10), `targetSdk = 34`, `compileSdk = 34`, ABI: `arm64-v8a` и `x86_64`.
- **Основные библиотеки:** Jetpack Compose BOM `2024.06.00`, Material 3 + `material-icons-extended`, Navigation Compose `2.8.0`, Lifecycle ViewModel Compose `2.8.0`, SplashScreen `1.0.1`, Room `2.6.1` (`room-runtime`, `room-ktx`, `room-compiler` через KSP), OkHttp `4.12.0`, `chesslib` `1.3.3`.
- **Сборка release и ProGuard/R8:** в `buildTypes.release` включены `isMinifyEnabled = true` и `isShrinkResources = true`. Правила в [app/proguard-rules.pro](../app/proguard-rules.pro) сохраняют:
  - `native <methods>` в `StockfishEngine` (привязанные в `stockfish_jni.cpp`);
  - классы `RoomDatabase`, `@Entity`, `@Dao`, `*_Impl`, `TypeConverters`, `domain.entity.**` и `values()`/`valueOf()` у всех `enum com.example.chessmentor.**`;
  - `com.github.bhlangonijr.chesslib.**` (использует reflection по фигурам/клеткам);
  - `Signature`, `InnerClasses`, `EnclosingMethod`, `RuntimeVisibleAnnotations`, `SourceFile`, `LineNumberTable`.
- **Манифест и облачный бэкап:** [AndroidManifest.xml](../app/src/main/AndroidManifest.xml) запрашивает только разрешение `INTERNET`, задаёт `windowSoftInputMode="adjustResize"`. Файлы [backup_rules.xml](../app/src/main/res/xml/backup_rules.xml) и [data_extraction_rules.xml](../app/src/main/res/xml/data_extraction_rules.xml) **исключают** из автобэкапа Android каталог `nnue` (~67 МБ, чтобы не гонять сеть через облако), локальную БД Room (`database="."` для `cloud-backup`) и `sharedpref` `user_session.xml` (чтобы после переустановки не возникало ссылок на несуществующий `userId`).

---

## Нативный движок Stockfish 19 и нейросеть NNUE

### C++ / JNI ([app/src/main/cpp](../app/src/main/cpp))

- [CMakeLists.txt](../app/src/main/cpp/CMakeLists.txt) компилирует `stockfish-lib` (`C++17`, `-O3 -fno-exceptions -Wall -Wcast-qual`, `NDEBUG`, `IS_64BIT`, `USE_POPCNT`, `USE_PREFETCH`, **`NNUE_EMBEDDING_OFF`**) из исходников Stockfish `sf_19` (`app/src/main/cpp/stockfish/src/`, без `main.cpp`).
- Архитектурные флаги: `USE_NEON=8` для `arm64-v8a`; `USE_SSE2 USE_SSSE3 USE_SSE41` + `-msse4.1 -mpopcnt` для `x86_64`.
- Линковка включает `-Wl,-z,max-page-size=16384` для обязательного **выравнивания ELF LOAD-сегментов по 16 КБ** (Android 15+), что проверяется в CI через `llvm-readelf`.
- [stockfish_bridge.h](../app/src/main/cpp/stockfish_bridge.h), [stockfish_bridge.cpp](../app/src/main/cpp/stockfish_bridge.cpp) и [stockfish_jni.cpp](../app/src/main/cpp/stockfish_jni.cpp) оборачивают `Stockfish::Engine`:
  - Все операции синхронизированы `opMutex_` (создание/поиск/настройки/разрушение), а `stop()` работает без захвата длинной блокировки через `stopRequested_` + `engine_->stop()`.
  - Если во время поиска была вызвана остановка (`stopRequested_.load(std::memory_order_acquire)` после `wait_for_search_finished()`), `StockfishBridge::search` возвращает `false` — обрезанный ответ малой глубины никогда не выдаётся за полноценный поиск.
  - Исключены утечки при повторном `init`: `destroy()` корректно сбрасывает текущий экземпляр.

### Kotlin-слой движка и NNUE ([data/engine](../app/src/main/java/com/example/chessmentor/data/engine), [domain/engine/EngineStatus.kt](../app/src/main/java/com/example/chessmentor/domain/engine/EngineStatus.kt))

- **Контракт [ChessEngine.kt](../app/src/main/java/com/example/chessmentor/data/engine/ChessEngine.kt):** реактивный `status: StateFlow<EngineStatus>`, `init()`, `ensureReadyOnStartup()`, `getBestMoveWithLine(fen, depthLimit)`, `evaluate(fen, depthLimit)`, `getBestMove(fen, depthLimit)`, `setOption(name, value)`, `cancelInit()`, `stop()`, `destroy()`. Матовые оценки кодируются как `±(100_000 - matePlies)` (`isMateScore` проверяет `abs(score) >= 90_000`).
- **Жизненный цикл в [StockfishEngine.kt](../app/src/main/java/com/example/chessmentor/data/engine/StockfishEngine.kt):**
  - `lifecycleEpoch: AtomicInteger` предотвращает гонку между параллельным `destroy()` и `init()`/`setOption()`/поиском.
  - Запросы к JNI сериализуются через `mutex`; `runCancellableSearch` при отмене корутины вызывает нативный `nativeStop()`.
  - `ensureReadyOnStartup()` идемпотентно запускает прогрев в `AppContainer.appScope`: не перезапускает активную загрузку и не перетирает явную паузу пользователя (`EngineErrorReason.CANCELLED`), но повторяет ранее аварийно сорванную попытку.
- **Загрузка и проверка NNUE ([NnueDownloader.kt](../app/src/main/java/com/example/chessmentor/data/engine/NnueDownloader.kt), `NnueIntegrity`):**
  - Файл сети: `nn-1a298aa575a0.nnue`, ожидаемый префикс SHA-256: `1a298aa575a0` (первые 12 hex-символов).
  - Официальные зеркала: `https://tests.stockfishchess.org/api/nn/nn-1a298aa575a0.nnue` и `https://github.com/official-stockfish/networks/raw/master/nn-1a298aa575a0.nnue`.
  - Скачивание идёт в `<filesDir>/nnue/nn-1a298aa575a0.nnue.part` (тайм-ауты `15s` connect / `60s` read), проверяется свободное место (`Content-Length + 16 МиБ` либо fallback `96 МиБ`), минимальный размер `1 МиБ`, потоковый SHA-256 без загрузки файла целиком в RAM, после чего файл атомарно переименовывается в финальный `nn-1a298aa575a0.nnue` и передаётся движку через UCI `EvalFile`.
- **Настройки поиска ([EngineSettings.kt](../app/src/main/java/com/example/chessmentor/domain/entity/EngineSettings.kt), [SharedPrefsEngineSettingsRepository.kt](../app/src/main/java/com/example/chessmentor/data/repository/SharedPrefsEngineSettingsRepository.kt)):**
  - Хранятся в `engine_settings.xml`: `depth` (`10..25`, default `15`), `threads` (`1..8`, default `2`), `hashSizeMb` (`16..256`, default `32`).
  - Предустановленные профили: `fast()` (`12 / 1 / 16 МБ`), `default()` (`15 / 2 / 32 МБ`), `deep()` (`22 / 4 / 128 МБ`).

---

## Хранение данных: Room v5, миграции, транзакции и SharedPreferences

### База данных Room ([AppDatabase.kt](../app/src/main/java/com/example/chessmentor/data/local/AppDatabase.kt), [DatabaseMigrations.kt](../app/src/main/java/com/example/chessmentor/data/local/DatabaseMigrations.kt))

Имя БД — `chess_mentor_db`, режим журналирования `WRITE_AHEAD_LOGGING`, при каждом открытии включается `PRAGMA foreign_keys = ON`. Версия схемы — **5** (экспортируется KSP в [5.json](../app/schemas/com.example.chessmentor.data.local.AppDatabase/5.json), проверяется на каждом CI).

| Таблица | Назначение и основные поля | Связи (FK) и индексы |
|---|---|---|
| `users` | Локальные профили: `id`, `nickname` (3..50), `email`, `passwordHash`, `rating` (0..3500, старт 1200), `skillLevel`, `createdAt`, `lastLogin`, `isPremium`, `isSoundEnabled`, `preferredTheme` | Корневая сущность |
| `games` | Партии: `id`, `userId`, `pgnData`, `playerColor`, `timeControl`, `playedAt`, `analysisStatus`, `analyzedAt`, `accuracy`, `averageEvaluationLoss`, `totalMistakes`, `blundersCount`, `mistakesCount`, `inaccuraciesCount`, `evaluationsJson` | FK → `users(id)` (`CASCADE`), индекс по `userId` |
| `analyzed_moves` | Пошаговый разбор: `id`, `gameId`, `moveIndex` (0-based), `moveNumber` (1-based), `color`, `quality`, `san`, `bestMove`, `evalBefore`, `evalAfter`, `evalLoss`, `comment` | FK → `games(id)` (`CASCADE`), индекс по `gameId` |
| `mistakes` | Ключевые ошибки партии: `id`, `gameId`, `themeId`, `moveNumber`, `color`, `evaluationLoss`, `mistakeType`, `bestMove`, `userMove`, `comment`, `fenBefore` | FK → `games(id)` (`CASCADE`), индекс по `gameId` |
| `exercises` | Задачи (общие и из партий): `id`, `fenPosition`, `solutionMoves`, `tacticalPattern`, `patternClusterId`, `difficultyRating` (0..100), `patternFeatures`, `createdFrom`, `sourceGameId`, `sourceMoveNumber`, `createdAt`, `timesShown`, `timesSolved`, `successRate` | Индексы по `tacticalPattern`, `difficultyRating`, `patternClusterId` |
| `exercise_attempts` | История попыток: `id`, `userId`, `exerciseId`, `solved`, `timeSpentSeconds`, `movesMade` (UCI-ходы + `@`-теги `AttemptMetadata`), `attemptedAt` | FK → `exercises(id)` (`CASCADE`), индекс по `exerciseId` |

- **Миграции `1 → 5` (`DatabaseMigrations.ALL_MIGRATIONS`):** все переходы (`1→2`, `2→3`, `3→4`, `4→5`) идемпотентны: перед `ALTER TABLE ... ADD COLUMN` проверяют фактические столбцы через `PRAGMA table_info(...)` и создают недостающие таблицы/индексы через `IF NOT EXISTS`. Деструктивная миграция разрешена **только при понижении версии APK** (`fallbackToDestructiveMigrationOnDowngrade()`).
- **Восстановление при старте процесса ([DatabaseMaintenance.kt](../app/src/main/java/com/example/chessmentor/data/local/DatabaseMaintenance.kt)):** при холодном старте (`restoreSessionIfNeeded`) сбрасывает застрявшие партии со статусом `IN_PROGRESS` обратно в `COMPLETED` (если есть сохранённые `analyzed_moves` / `evaluationsJson`) или `PENDING`, а также удаляет осиротевшие персональные упражнения (`ExerciseSource.MISTAKE`), чья `sourceGameId` больше не существует.
- **Транзакции и ограничения SQLite:**
  - [RoomTransactionRunner.kt](../app/src/main/java/com/example/chessmentor/data/local/RoomTransactionRunner.kt) открывает `AppDatabase.withTransaction` строго на `Dispatchers.IO`, чтобы разделять один корутинный контекст с Room-репозиториями (требование Room 2.6).
  - [RoomMistakeRepository.kt](../app/src/main/java/com/example/chessmentor/data/repository/room/RoomMistakeRepository.kt) разбивает `findByGameIds(...)` на чанки по 500 ID (лимит SQLite `SQLITE_MAX_VARIABLE_NUMBER = 999`), а [RoomAnalyzedMoveRepository.kt](../app/src/main/java/com/example/chessmentor/data/repository/room/RoomAnalyzedMoveRepository.kt) оборачивает вызовы DAO в `withContext(Dispatchers.IO)` наравне с остальными репозиториями.
  - [TypeConverters.kt](../app/src/main/java/com/example/chessmentor/data/local/TypeConverters.kt) сериализует `List<String>` в безопасный JSON-массив (с обратной совместимостью при чтении legacy-строк через запятую) и enum-ы с безопасным fallback на значения по умолчанию.

### Сессия, пароли, настройки и тестовый профиль

- **Хеширование паролей ([PasswordHasher.kt](../app/src/main/java/com/example/chessmentor/domain/security/PasswordHasher.kt)):** `PBKDF2WithHmacSHA256`, `310_000` итераций, случайная соль 16 байт, 256-битный ключ, сравнение через `MessageDigest.isEqual` за постоянное время. Формат записи: `pbkdf2_sha256$<iter>$<salt_b64>$<hash_b64>`. Устаревшие записи вида `hashed_<пароль>` автоматически перешифровываются в PBKDF2 при успешном входе (`LoginUserUseCase`).
- **Сессия и предпочтения ([SharedPrefsSessionRepository.kt](../app/src/main/java/com/example/chessmentor/data/repository/SharedPrefsSessionRepository.kt)):** приватный `user_session.xml` хранит `active_user_id`, `last_login_email`, `app_theme_mode` (`SYSTEM`/`LIGHT`/`DARK`), подсказку ручного PGN-ника `chess_username_hint_<userId>` и ник публичного архива Chess.com `chesscom_source_<userId>`. Пароли и хеши в `SharedPreferences` не хранятся.
- **Локальный отладочный профиль ([TesterProfiles.kt](../app/src/main/java/com/example/chessmentor/data/local/TesterProfiles.kt)):** работает только при `BuildConfig.DEBUG == true`; маркер `local-debug-persona:v1` не является ни PBKDF2-хешем, ни legacy-паролем, поэтому обычный вход по паролю никогда не откроет профиль тестировщика (и наоборот). В release полностью отключён.

---

## Доменные алгоритмы: PGN, разбор партий, тренировки и статистика

### Парсинг, экспорт и дедупликация PGN ([domain/pgn](../app/src/main/java/com/example/chessmentor/domain/pgn), [ChessMoveUtils.kt](../app/src/main/java/com/example/chessmentor/domain/util/ChessMoveUtils.kt))

- [PgnParser.kt](../app/src/main/java/com/example/chessmentor/domain/pgn/PgnParser.kt):
  - Удаляет UTF-8 BOM, комментарии `{...}` и `;...`, вложенные варианты `(...)`, суффиксы `!`/`?` и NAG `$N`, номера ходов и самостоятельный токен взятия на проходе `e.p.`. Распознаёт оба варианта ничьей `1/2-1/2` и `½-½`.
  - Поддерживает кастомную стартовую позицию через тег `[FEN "..."]` и `[SetUp "1"]`; отклоняет нешахматные варианты (Chess960 и т.п.).
  - Извлекает метаданные: игроков, Эло, дату, результат (`1-0`/`0-1`/`1/2-1/2`/`*`), ECO, дебют, контроль времени (`parseTimeControl`) и аннотации часов `[%clk H:MM:SS]`.
  - Пакетная нарезка (`splitGames`) поддерживает многотеговые строки заголовков и строчный текст ходов.
  - **Канонический отпечаток (`computeCanonicalFingerprint`):** сначала проверяет нормализованный URL конкретной партии (`[Link]`/`[Site]`); иначе для длинных партий (`>= 16` полуходов) использует стартовый FEN + каноническую основную линию, а для коротких (`< 16` полуходов) дополнительно включает имена игроков, дату и результат.
  - **Лимиты пакетного импорта:** предпросмотр и [UploadGameUseCase](../app/src/main/java/com/example/chessmentor/domain/usecase/UploadGameUseCase.kt) обрабатывают до **50** блоков за один пакет (остаток отражается в `truncatedCount`), а файл `ImportUserBackupUseCase` / `UploadGameUseCase` группируется по стороне игрока.
- [PgnArchiveExporter.kt](../app/src/main/java/com/example/chessmentor/domain/pgn/PgnArchiveExporter.kt):
  - При экспорте корректно экранирует кавычки/обратные слеши внутри значений PGN-тегов без дублирования строк, нормализует `½-½` → `1/2-1/2` и не теряет токен результата из-за `;`-комментариев в конце строки.

### Классификация ходов и анализ партий ([domain/analysis](../app/src/main/java/com/example/chessmentor/domain/analysis), [OpeningDatabase.kt](../app/src/main/java/com/example/chessmentor/domain/opening/OpeningDatabase.kt), [AnalyzeGameUseCase.kt](../app/src/main/java/com/example/chessmentor/domain/usecase/AnalyzeGameUseCase.kt))

- **Win%-модель (`GameMoveClassifier`):** перевод оценки позиции в вероятность победы `Win% = 50 + 50 * (2 / (1 + exp(-0.00368208 * eval)) - 1)` с ограничением не-матовых оценок в диапазоне `[-1000, +1000]` сП (матовые оценки дают `100%` / `0%`).
- **Пороги `MoveQuality` по потере `ΔWin%` (в процентных пунктах):**
  - `BOOK`: совпадение с деревом дебютов ([OpeningDatabase](../app/src/main/java/com/example/chessmentor/domain/opening/OpeningDatabase.kt) — **28** дебютных позиций и табий, до 15-го / 12-го хода при перестановке) и `ΔWin% <= 5.5`;
  - `BRILLIANT` (`!!`): подтверждённая легальная прямая или косвенная жертва материала от `180` сП (`detectMaterialSacrifice`, обычные размены отсеиваются) при точном ходе в не выигранной заранее позиции;
  - `GREAT_MOVE` (`!`): точное наказание ошибки соперника (`ΔWin%` соперника `>= 12`), нахождение форсированного мата в 2..10 ходов или единственная защита под шахом (исключая очевидные обратные взятия);
  - `BEST_MOVE`: лучший ход движка или `ΔWin% <= 0.5` и потеря `<= 8` сП;
  - `EXCELLENT`: `ΔWin% <= 2.0`;
  - `GOOD`: `ΔWin% <= 5.5` (также технические упрощения при перевесе `>= +5.0 → +4.5` и падения в безнадёжных позициях `<= -6.0` не раздувают счётчики зевков);
  - `INACCURACY` (`?!`): `ΔWin% <= 10.5`;
  - `MISTAKE` (`?`): `ΔWin% <= 20.0` (если было `winBefore >= 76%`, `ΔWin% >= 18%` и `winAfter >= 42%` — `MISSED_WIN`);
  - `BLUNDER` (`??`): `ΔWin% > 20.0` (или `MISSED_WIN`, если при `winBefore >= 74%` позиция осталась не проигранной `winAfter >= 42%`).
- **Точность, ACPL и фазы:** точность хода `103.1668 * exp(-0.04354 * winDrop) - 3.1669` (`0..100`). Итоговая точность партии — среднее арифметическое между гармоническим средним и средним, взвешенным по волатильности позиции в скользящем окне. На каждый полуход выполняется один поиск Stockfish на заданной глубине (переиспользуется как `beforeLine` следующего полухода; мат/пат/ничья обрабатываются без лишнего вызова движка), а сбой поиска на любом полуходе прерывает разбор (`EngineAnalysisException`). Точность и `averageCpLoss` (с исключением ходов `BOOK` при наличии самостоятельных ходов) считаются для обеих сторон, но персональные метрики `Game` и таблица `mistakes` сохраняются строго по `Game.playerColor`. В сводке одной партии фазы (`OPENING` / `MIDDLEGAME` / `ENDGAME`, минимум 2 хода) определяются монотонно по материалу на доске, а в сводном отчёте [PlayerAnalyticsCalculator](../app/src/main/java/com/example/chessmentor/domain/statistics/PlayerAnalyticsCalculator.kt) — по номерам ходов (`1–12` / `13–30` / `31+`); цейтнот считается при остатке часов `{[%clk]}` строго `< 20 сек`.
- **Семантический разбор и перенос в обучение:** [SemanticMistakeAnalyzer.kt](../app/src/main/java/com/example/chessmentor/domain/analysis/SemanticMistakeAnalyzer.kt) и [ExerciseClusterer.kt](../app/src/main/java/com/example/chessmentor/domain/clustering/ExerciseClusterer.kt) классифицируют 37 тактических паттернов (`TacticalPattern`) и 8 укрупнённых тем (`TacticalTheme`). [PuzzleQualityEvaluator.kt](../app/src/main/java/com/example/chessmentor/domain/training/PuzzleQualityEvaluator.kt) и [GenerateExercisesFromMistakesUseCase.kt](../app/src/main/java/com/example/chessmentor/domain/usecase/GenerateExercisesFromMistakesUseCase.kt) отсеивают безнадёжные позиции (`<= -550 сП`) и позиционные неточности (в позициях `>= +700 сП` берут только грубые провалы `>= 400 сП`), проверяют единственность тактики (глубина `8` / решение `12`) и обрезают главную линию (PV) до **нечётного** числа полуходов (`trimPvToPlayerLastMove`), чтобы позиция всегда заканчивалась ходом игрока.

### Тренировка, повторение и статистика

- **Банк и режимы (`CuratedExerciseBank`, `TrainingMode`):** **46** встроенных тактических задач + личные задачи из ошибок партий. Режимы ([TrainingMode.kt](../app/src/main/java/com/example/chessmentor/domain/training/TrainingMode.kt)): `Daily` (детерминированная задача дня без повторов), `SmartSession` (10 задач по 5 фазам `SessionPhase`: `WARMUP` [1–2] → `REVIEW_AND_MISTAKES` [3–5] → `CORE_INTERLEAVED` [6, 8] + `DEFENSE_CHECK` [7] → `DEEP_CALCULATION` [9–10]), `All`, `MyMistakes`, `WeakPatterns`, `PatternVision` («Зоркость»), `DeepCalculation` («Расчёт»), `Defense` («Защита»), `ByTheme(theme)` (7 выбираемых тем из `selectableThemes`) и `FromGame(gameId, focusMoveNumber)`.
- **Интервальное повторение (`SpacedRepetitionScheduler`):** гибрид 6 коробок Лейтнера (`0..5`) + SM-2 `easeFactor` (`1.3..2.8`, старт `2.5`). Метаданные кодируются внутри `ExerciseAttempt.movesMade` с префиксом `@` (`AttemptMetadata`: `@HINT:PIECE`, `@HINT:FULL`, `@REVEALED`, `@RETRY:N`, `@FIRST_MS:T`, `@IMPULSE`) без изменения схемы БД. Ошибки и дорешивание в пределах `10 мин` группируются в один подход (`ExerciseSitting`): провал/показ решения → коробка 0 (охлаждение `10 мин`); решение со 2-й+ попытки → коробка 1 (`4 ч`); с подсказкой фигуры → коробка 1 (`1 дн.`); чистое долгое → коробка 1 (`1 дн.`); чистое беглое → коробка 2 (`3 дн.`) с дальнейшим ростом до `7 дн.` → `21 дн.` → до `90 дн.`. Очередь чередования тем — [InterleavedPatternQueue.kt](../app/src/main/java/com/example/chessmentor/domain/training/InterleavedPatternQueue.kt).
- **Калиброванный рейтинг Эло (`TrainingRatingCalculator`):** `MIN_RATING = 100`, `MAX_RATING = 3500`, `puzzleElo = 800 + effectiveDifficulty * 16` (`800..2400`). Симметричный коэффициент `K`: `40` (`< 10` подходов), `28` (`10..29`), `22` (`30+`). Множители к прибавке: подсказка фигуры `×0.50`, 1 ошибка перед дорешиванием `×0.30`, 2+ ошибки `×0.15`, показ хода/решения `= 0`. Повторные ошибки-угадывания несут малый штраф (`-3` на 2-й, `-2` на 3-й). Детектор импульсивности (`isImpulsiveMistake`) помечает поспешный провал первым ходом (от `150 мс` до порога `3.0–6.0 с` с поправками режима).
- **Проверка альтернативных ходов (`TrainingMoveVerifier`):** при ходе игрока, не совпадающем с записанным в задаче (и не дающем мат), проверяется оценка Stockfish. При потере движка или ошибке анализа возвращается `VerificationResult.Unavailable` (`checkUnavailable = true`, `pendingCheck` сохраняется для повтора проверки или возврата позиции; `CancellationException` пробрасывается).
- **Резервная копия JSON (`UserProgressBackupCodec` v1, `SimpleJson`):** без сторонних JSON-библиотек — собственный строгий парсер [SimpleJson.kt](../app/src/main/java/com/example/chessmentor/domain/backup/SimpleJson.kt) (ограничение вложенности, точные `Long`/`Int`, контроль экранирования) и кодек [UserProgressBackupCodec.kt](../app/src/main/java/com/example/chessmentor/domain/backup/UserProgressBackupCodec.kt) (`FORMAT_ID = "chessmentor.user_backup"`, `CURRENT_SCHEMA_VERSION = 1`). Валидирует SHA-256 всей полезной нагрузки за постоянное время (`MessageDigest.isEqual`). Пароль, активная сессия, ник источника Chess.com и HTTP-кеш в JSON не включаются.

---

## Слой представления: ViewModel и UI-инфраструктура

- **5 ViewModel** создаются через [ChessMentorViewModelFactory.kt](../app/src/main/java/com/example/chessmentor/presentation/viewmodel/ChessMentorViewModelFactory.kt):
  1. `GameViewModel` — сессия, загрузка партий и ошибок (`userGames`, `mistakesByGameId`), отмена гонок `selectGameJob`, превью и загрузка PGN / Chess.com, управление запуском/отменой анализа и `libraryAnalysisRequestedGameId`, транзакционное удаление партии `deleteGame`.
  2. `BoardViewModel` — интерактивный разбор и свободная ветка вариаций, навигация по полуходам и ключевым моментам (`GameReviewHelper`), проверка альтернативных ходов при исправлении ошибок (`RetryMistakePanel`), живой анализ позиции Stockfish (`isCheckingEngine` защищает `requestRetryHint()`), расчёт часов и материального баланса.
  3. `TrainingViewModel` — цикл тренировки (`TrainingUiState`), проверка альтернативных ходов (`pendingCheck` / `checkUnavailable`), система пошаговых подсказок и полного раскрытия (`onRevealSolution`), таймер, расписание повторений и итоговая карточка сессии.
  4. `StatisticsViewModel` + `StatisticsPeriodLayout` (`StatisticsScreen`) — переключение периодов `StatsPeriod` (`ALL`, `LAST_30_DAYS`, `LAST_7_DAYS`) без смешения старых и новых срезов, локальный кэш последнего успешного отчёта по каждому периоду в `StatisticsPeriodLayout` и явный повтор при ошибке.
  5. `SettingsViewModel` — редактирование профиля/темы/звука, смена пароля (`ChangePasswordUseCase`), экспорт/импорт `.json` и экспорт `.pgn`.
- **Звук и палитры:** [SoundManager.kt](../app/src/main/java/com/example/chessmentor/presentation/ui/components/SoundManager.kt) генерирует звуки ходов, взятий, шаха, мата, успеха и ошибки через `ToneGenerator` на `Dispatchers.IO`. Цветовые роли заданы в [ChessPalette.kt](../app/src/main/java/com/example/chessmentor/presentation/ui/components/theme/ChessPalette.kt), [Color.kt](../app/src/main/java/com/example/chessmentor/presentation/ui/components/theme/Color.kt), [Theme.kt](../app/src/main/java/com/example/chessmentor/presentation/ui/components/theme/Theme.kt); 4 темы доски (`Классическая`, `Синяя`, `Зеленая`, `Дерево` из `BoardThemes`) независимы от светлой/тёмной/системной темы приложения.

---

## Сборка из исходников (не обязательна для тестировщика)

Требуются JDK 17, Gradle Wrapper из репозитория, Android SDK Platform 34 / Build Tools `34.0.0`, NDK `26.1.10909125` и CMake `3.22.1`. Минимальный API `29`, ABI `arm64-v8a` и `x86_64`. При обновлении зависимостей или скриптов сверяться с [app/build.gradle.kts](../app/build.gradle.kts) и [Android CI](../.github/workflows/android-ci.yml). Из корня проекта:

```sh
bash gradlew :app:assembleDebug :app:assembleRelease
bash gradlew :app:testDebugUnitTest :app:lintDebug :app:compileDebugAndroidTestKotlin
python3 -m unittest discover -s .github/scripts
# Нужен подключённый Android или запущенный эмулятор (API 29+, arm64-v8a / x86_64):
bash gradlew :app:connectedDebugAndroidTest
```

В Windows вместо `bash gradlew` использовать `gradlew.bat`. Устанавливаемый файл локальной debug-сборки — `app/build/outputs/apk/debug/app-debug.apk`. Для релиза не использовать одноразовую подпись CI; [процесс публикации](android-release.md) отделён от сборки и тестов.

## Как поддерживать документацию вместе с кодом

При изменении пользовательского действия обновить строку его компонента в [карте интерфейса](android-ui-inventory.md): откуда вход, когда кнопка доступна, что меняется, что происходит при ошибке/отмене, куда ведёт возврат. Для сетевого источника также описать лимиты и дубликаты. При добавлении миграции Room, сущности, теста или доменного алгоритма обновить таблицы в `development.md` и реестр тестов в [testing.md](testing.md). Указать тест или пометку «проверить вручную»; план не описывать как реализованное поведение. Исторический зелёный запуск относится к конкретному SHA, а не ко всем будущим версиям.
