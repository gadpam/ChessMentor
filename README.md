# ChessMentor

[![Android CI](https://github.com/gadpam/ChessMentor/actions/workflows/android-ci.yml/badge.svg)](https://github.com/gadpam/ChessMentor/actions/workflows/android-ci.yml)
![Platform](https://img.shields.io/badge/Android-10%2B%20(API%2029–34)-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0.0-7F52FF?logo=kotlin&logoColor=white)
![UI](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white)
![Engine](https://img.shields.io/badge/Stockfish-19%20NNUE%20(JNI%20C%2B%2B17)-1E88E5)
![Database](https://img.shields.io/badge/Room-v5%20SQLite-00897B)
![License](https://img.shields.io/badge/License-GPLv3-blue.svg)

**ChessMentor** — офлайн-ориентированное Android-приложение персонального шахматного тренера. Приложение анализирует партии пользователя на локальном нативном движке **Stockfish 19 (NNUE)** через C++17/JNI, классифицирует каждый ход по модели вероятности победы **Win%**, объясняет тактические и позиционные причины ошибок на русском языке, автоматически превращает допущенные промахи в интерактивные задачи, планирует повторение по гибридному алгоритму **SM-2 + коробки Лейтнера** и строит глубокую аналитику игры с персональным планом тренировок.

---

## Содержание

- [Ключевые возможности](#ключевые-возможности)
- [Архитектурный обзор](#архитектурный-обзор)
- [Технологический стек](#технологический-стек)
- [Структура репозитория](#структура-репозитория)
- [Быстрый старт](#быстрый-старт)
- [Полная документация (`docs/`)](#полная-документация-docs)
- [Безопасность и приватность](#безопасность-и-приватность)
- [Лицензия и сторонние компоненты](#лицензия-и-сторонние-компоненты)

---

## Ключевые возможности

### 1. Локальный движок Stockfish 19 (NNUE)
- Встроенная нативная библиотека `libstockfish-lib.so` (C++17, `arm64-v8a` с `USE_NEON=8` и `x86_64` с `SSE4.1/POPCNT`), выровненная по страницам **16 КБ** для совместимости с Android 15+.
- Однократная фоновая загрузка официальной нейросети `nn-1a298aa575a0.nnue` с живым прогрессом, резервным зеркалом, атомарной установкой через `.part` и потоковой проверкой **SHA-256** — после установки приложение работает **100% офлайн**.
- Гибкая настройка глубины (`10..25` полуходов), числа потоков CPU (`1..8`) и размера хеш-таблицы (`16..256 МБ`) с готовыми профилями («Быстрый», «Стандартный», «Глубокий»).

### 2. Импорт PGN и двусторонний анализ партий
- Импорт одиночных и мульти-партийных архивов **PGN** (Lichess, Chess.com, ChessBase, ручной ввод, нестандартный стартовый `[FEN]`), очистка вложенных вариантов, комментариев и NAG, извлечение показаний часов `[%clk]`, строгая FIDE-валидация каждого хода, автоопределение цвета игрока и дедупликация по отпечатку партии.
- Оценка ходов **обеих сторон** по откалиброванной логистической кривой `Win%` (методика Lichess Stockfish NNUE) и **10 категорий качества хода**: `BRILLIANT` (💎), `GREAT_MOVE` (👍), `BEST_MOVE` (⭐), `EXCELLENT` (✅), `GOOD` (✓), `BOOK` (📖), `INACCURACY` (⚡), `MISTAKE` (⚠️), `BLUNDER` (❌), `MISSED_WIN` (💔).
- Расчёт точности партии (среднее гармонического и взвешенного по волатильности значений) и ACPL отдельно для белых, чёрных и трёх фаз (`Дебют` / `Миттельшпиль` / `Эндшпиль`).
- Дерево дебютной теории (**28** именованных табий ECO + **44** канонических главных варианта с распознаванием перестановок ходов) и семантический разбор **13** тактических мотивов (`SemanticMistakeAnalyzer`).

### 3. Интерактивный разбор партии и сводка
- Сводный экран разбора (`SummaryScreen`) с интерактивным графиком оценки, ключевыми переломными моментами, сравнением сторон, разбивкой по фазам и переходом к отработке ошибок конкретной партии в один тап.
- Пошаговый просмотр (`GameViewScreen`) со шкалой оценки, двойными стрелками (сыгранный и сильнейший ход), часами игроков, живым анализом Stockfish, режимом **«Исправить ошибку»** на доске и свободным вводом побочных вариантов («а что если?»).

### 4. Адаптивный тренажёр и интервальное повторение SM-2
- Два источника задач: автоматически сгенерированные задачи из собственных ошибок (с фильтром учебной ценности `PuzzleQualityEvaluator` и кластеризацией по **37** тактическим паттернам) и встроенный офлайн-банк из **46** выверенных кураторских позиций (`CuratedExerciseBank`).
- **10 режимов тренировки**: «Все задачи», структурированная «Сессия 10» (разминка → повторение и свои ошибки → чередование мотивов → проверка защиты), «Мои ошибки», «Слабые темы», «Зоркость», «Расчёт», «Защита», «Задача дня», тренировка по теме и отработка ошибок выбранной партии.
- Многоходовые форсированные решения с автоматическим ответом соперника, поддержка превращения (включая слабое превращение в коня/ладью/слона), принятие альтернативных выигрышей, двухуровневые подсказки (подсветка фигуры → полный ход), детекция импульсивных ошибок и пошаговый разбор решения.
- Гибридный планировщик **SM-2 + 5 коробок Лейтнера** (`SpacedRepetitionScheduler`), внутрисессионное чередование мотивов (`InterleavedPatternQueue`) и честная рейтинговая система Эло без инфляции (`TrainingRatingCalculator` с калибровочным `K = 40 → 28 → 22`).

### 5. Тренерская аналитика и переносимость данных
- Многомерная статистика (`PlayerAnalyticsCalculator`): фильтры по периоду (`7 дней` / `30 дней` / `Всё время`), игра белыми и чёрными (W/D/L, шахматный счёт, лучший побеждённый Эло), дебютный репертуар ECO с оценкой на выходе из дебюта, фазы партии, контроли времени, диагностика цейтнота, наказание зевков соперника, освоение паттернов, динамика точности и рейтинга, 14-дневная шкала активности и приоритизированный план тренера с прямыми переходами к нужным режимам.
- Экспорт библиотеки в стандартный мульти-партийный архив **`.pgn`** и **полный переносимый JSON-бэкап** (`schemaVersion = 1`) с контрольной суммой **SHA-256** и идемпотентным восстановлением без дублей (профиль, настройки, партии, ходы, ошибки, персональные задачи и полная история SM-2).

---

## Архитектурный обзор

Проект построен на принципах **Clean Architecture + MVVM + UDF (Unidirectional Data Flow)** с реактивными потоками `StateFlow`, чистым доменным ядром (тестируемым в JVM без Android-эмулятора), локальным хранилищем **Room v5** и изолированным нативным мостом **JNI → Stockfish 19**.

```mermaid
flowchart TB
    subgraph UI["Presentation (Jetpack Compose + Material 3)"]
        Screens["9 экранов + 13 UI-компонентов\nChessMentorNavigation"]
        VMs["ViewModels (StateFlow)\nGame · Board · Training · Statistics · Settings"]
        Screens <--> VMs
    end

    subgraph Domain["Domain (бизнес-логика, математические модели, контракты)"]
        UCs["10 Use Cases\nAuth · Upload · Analyze · GenerateExercises\nGetTrainingExercise · Statistics · Backup"]
        Models["Аналитические и учебные модули\nGameMoveClassifier · SemanticMistakeAnalyzer · OpeningDatabase\nPgnParser · SpacedRepetitionScheduler · TrainingRatingCalculator\nPuzzleQualityEvaluator · PlayerAnalyticsCalculator · UserProgressBackupCodec"]
        Repos["7 интерфейсов Repository + 19 доменных сущностей"]
        UCs --> Models
        UCs --> Repos
    end

    subgraph Data["Data & Native (Android / Room / C++17 JNI)"]
        RoomImpl["Room v5 SQLite (6 таблиц, 7 индексов)\nDatabaseMigrations (1→5) · DatabaseMaintenance"]
        PrefsImpl["SharedPreferences\nuser_session · engine_settings"]
        EngineKt["StockfishEngine + NnueDownloader\n(SHA-256 верификация nn-1a298aa575a0.nnue)"]
        EngineCpp["libstockfish-lib.so (C++17 JNI)\nStockfishBridge + Stockfish 19 (arm64-v8a / x86_64)"]
        EngineKt <-->|"JNI"| EngineCpp
    end

    VMs --> UCs
    Repos <|.. RoomImpl
    Repos <|.. PrefsImpl
    UCs --> EngineKt
    VMs -.->|"Live-оценка / статус NNUE"| EngineKt
```

Ручное внедрение зависимостей сосредоточено в потокобезопасном контейнере [`AppContainer`](app/src/main/java/com/example/chessmentor/di/AppContainer.kt) и фабрике [`ChessMentorViewModelFactory`](app/src/main/java/com/example/chessmentor/presentation/viewmodel/ChessMentorViewModelFactory.kt) без кодогенерации DI-фреймворков.

---

## Технологический стек

| Категория | Технология | Версия / параметры |
|---|---|---|
| Платформа | Android | `minSdk = 29` (Android 10), `targetSdk = 34`, `compileSdk = 34` |
| Языки | Kotlin / C++ | Kotlin `2.0.0` (JVM target `17` + `desugar_jdk_libs 2.0.4`), C++17 |
| Система сборки | Gradle + AGP + KSP | Gradle `8.5`, AGP `8.2.2`, KSP `2.0.0-1.0.21` |
| UI | Jetpack Compose + Material 3 | Compose BOM `2024.06.00`, Activity Compose `1.9.0`, Core SplashScreen `1.0.1` |
| Архитектура и асинхронность | AndroidX Lifecycle + Coroutines | `lifecycle-viewmodel-compose 2.8.0`, `StateFlow` / `Flow` |
| База данных | AndroidX Room (SQLite) | `2.6.1`, схема `v5` (`exportSchema = true`, миграции `1→5`) |
| Шахматный движок | Stockfish `sf_19` + JNI | NDK `26.1.10909125`, CMake `3.22.1`, ABI `arm64-v8a` и `x86_64`, ELF 16 КБ |
| Правила шахмат / PGN / FEN | `bhlangonijr/chesslib` | `1.3.3` (JitPack) + строгая обёртка `ChessMoveUtils` |
| Криптография и целостность | Стандартный JCE / JDK | `PBKDF2WithHmacSHA256` (310 000 итераций), `SHA-256` (NNUE и JSON-бэкап) |
| Релизная оптимизация | R8 + Resource Shrinking | `isMinifyEnabled = true`, `isShrinkResources = true` (~**9.0 МБ** release APK) |
| Тестирование и качество | JUnit 4 + Android Lint + GitHub Actions | **98** JVM unit-тестов (21 класс), `lintDebug`, проверка схемы Room и 16 КБ ELF |

---

## Структура репозитория

```text
ChessMentor/
├── .github/workflows/
│   └── android-ci.yml              # CI: JUnit, Room v5 schema, Lint, Debug/R8 Release APK, 16 КБ ELF
├── app/
│   ├── build.gradle.kts            # Конфигурация модуля, NDK/CMake, KSP, R8 и Android Lint
│   ├── proguard-rules.pro          # Правила R8/ProGuard для JNI, Room, enum и chesslib
│   ├── schemas/                    # Версионированные JSON-схемы Room (AppDatabase/5.json)
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── cpp/                # StockfishBridge, JNI-обёртка, CMakeLists.txt и Stockfish sf_19
│       │   ├── java/com/example/chessmentor/
│       │   │   ├── MainActivity.kt
│       │   │   ├── di/             # AppContainer (внедрение зависимостей)
│       │   │   ├── data/           # engine/, local/ (Room + DAO + миграции), repository/
│       │   │   ├── domain/         # entity/, repository/, usecase/, analysis/, opening/,
│       │   │   │                   # pgn/, training/, clustering/, statistics/, backup/,
│       │   │   │                   # engine/, security/, session/, util/
│       │   │   └── presentation/   # viewmodel/, ui/components/ (screen/, navigation/, theme/, util/)
│       │   └── res/                # Векторные фигуры, темы Material 3, строки, правила Android Backup
│       ├── test/                   # 21 класс чистых JVM unit-тестов (98 тестов)
│       └── androidTest/            # Инструментальные тесты (с доступом к app/schemas)
├── docs/                           # Полная техническая и пользовательская документация
├── gradle/                         # Gradle Wrapper 8.5 и каталог версий libs.versions.toml
├── LICENSE                         # GNU General Public License v3.0
└── THIRD_PARTY_NOTICES.md          # Сведения о сторонних компонентах (Stockfish sf_19)
```

Объём кодовой базы: **113** основных Kotlin-файлов (~41,2 тыс. строк), **4** файла нативного C++/CMake-моста поверх исходников Stockfish 19, **21** файл модульных тестов (~4,9 тыс. строк, **98** тестов) и **32** файла ресурсов Android.

---

## Быстрый старт

### Требования к окружению
- **JDK 17** (рекомендуется Eclipse Temurin 17; используется также в Android Studio Koala / Ladybug+).
- **Android SDK Platform 34**, **Build-Tools 34.0.0**.
- **Android NDK `26.1.10909125`** и **CMake `3.22.1`** (устанавливаются через *SDK Manager → SDK Tools* или `sdkmanager`).
- Устройство или эмулятор на **Android 10+ (API 29+)** с архитектурой `arm64-v8a` (физические смартфоны/планшеты, Apple Silicon AVD) или `x86_64` (эмулятор на ПК).

### Сборка и запуск из командной строки

```bash
# 1. Клонирование репозитория
git clone https://github.com/gadpam/ChessMentor.git
cd ChessMentor

# 2. Запуск всех JVM unit-тестов (NDK и эмулятор не требуются)
./gradlew testDebugUnitTest

# 3. Статический анализ Android Lint
./gradlew lintDebug

# 4. Сборка отладочного APK (компилирует Stockfish 19 через CMake/NDK)
./gradlew assembleDebug

# 5. Сборка минифицированного релизного APK (R8 + сжатие ресурсов)
./gradlew assembleRelease

# 6. Установка отладочной сборки на подключённое устройство/эмулятор
./gradlew installDebug
```

Готовые артефакты (Debug APK, R8 Release APK, `mapping.txt`, отчёты тестов и Lint) также автоматически собираются в разделе **Actions** каждого коммита и Pull Request.

### Первый запуск на устройстве
1. Создайте локальный профиль (никнейм, email, пароль и ориентировочный стартовый рейтинг).
2. При старте приложение в фоне скачает официальную нейросеть Stockfish 19 (`nn-1a298aa575a0.nnue`, требуется ~96 МБ свободного места) и проверит её контрольную сумму SHA-256. Прогресс отображается на карточке движка и не блокирует навигацию, импорт PGN или решение задач из встроенного банка.
3. После появления статуса **«NNUE готов»** анализ партий, живая оценка позиций, генерация задач из ошибок и тренажёр работают полностью без интернета.

---

## Полная документация (`docs/`)

| Документ | О чём |
|---|---|
| [**01. Архитектура приложения**](docs/01-architecture.md) | Слои Clean Architecture + MVVM, полная карта всех 113 файлов, граф DI (`AppContainer`), контракты 7 репозиториев, 10 Use Cases, 5 ViewModels, навигация и модель многопоточности. |
| [**02. Движок Stockfish 19 и анализ партий**](docs/02-engine-and-analysis.md) | C++17 мост `StockfishBridge` и JNI, кодировка оценок и матов, загрузка и SHA-256 проверка NNUE, конечный автомат `EngineStatus`, PGN-парсер, математическая модель `Win%` и точности Lichess, 10 категорий качества ходов, дебютная книга и семантический анализ ошибок. |
| [**03. Адаптивный тренажёр, SM-2 и аналитика**](docs/03-training-and-analytics.md) | Генерация задач из ошибок и фильтр `PuzzleQualityEvaluator`, кураторский банк (46 задач), кластеризация 37 паттернов, 10 режимов и «Сессия 10», алгоритм интервального повторения SM-2 + Лейтнер, рейтинговая модель Эло без инфляции и расчёт статистики `PlayerAnalyticsCalculator`. |
| [**04. База данных Room v5, бэкап и безопасность**](docs/04-database-backup-and-security.md) | ER-диаграмма и полная спецификация 6 таблиц SQLite, 7 индексов и `TypeConverters`, миграции `1→5`, самовосстановление `DatabaseMaintenance`, `SharedPreferences`, формат JSON-бэкапа `v1` с SHA-256, PGN-экспорт, PBKDF2-HMAC-SHA256 и правила Android Backup. |
| [**05. Руководство пользователя**](docs/05-user-guide.md) | Подробное описание всех 9 экранов, расшифровка значков и цветов качества ходов, сценарии импорта, разбора партий, тренировок, чтения отчётов, настройки движка, резервного копирования и FAQ. |
| [**06. Сборка, CI/CD, R8 и тестирование**](docs/06-build-ci-and-testing.md) | Настройка окружения, нативная сборка и 16 КБ ELF alignment, разбор правил `proguard-rules.pro` и R8, устройство GitHub Actions CI, каталог всех 21 тестовых классов (98 тестов) и практические чек-листы по развитию проекта. |

---

## Безопасность и приватность

- **Локальное хранение без телеметрии:** партии, оценки, ошибки и история тренировок хранятся исключительно в локальной БД SQLite на устройстве. Единственное сетевое обращение — однократная загрузка файла весов NNUE с официальных серверов Stockfish по HTTPS.
- **Защита паролей:** пароли хешируются через `PBKDF2WithHmacSHA256` (**310 000** итераций по рекомендации OWASP, случайная 128-битная соль `SecureRandom`, 256-битный ключ, сравнение за постоянное время `MessageDigest.isEqual`).
- **Безопасные резервные копии:** JSON-бэкап защищён контрольной суммой **SHA-256** по каноническому `payload` и никогда не включает `passwordHash` или идентификаторы сессии; правила `backup_rules.xml` и `data_extraction_rules.xml` исключают `user_session.xml` и тяжёлый каталог `nnue/` из облачного бэкапа и переноса между устройствами.

Подробнее — в разделе [04. База данных, бэкап и безопасность](docs/04-database-backup-and-security.md).

---

## Лицензия и сторонние компоненты

Проект **ChessMentor** распространяется на условиях лицензии **GNU General Public License v3.0** — см. файл [`LICENSE`](LICENSE).

В состав приложения входит шахматный движок [Stockfish](https://github.com/official-stockfish/Stockfish) (ветка/релиз `sf_19`, GPLv3) и библиотека [`bhlangonijr/chesslib`](https://github.com/bhlangonijr/chesslib) (Apache 2.0). Подробное описание модификаций и уведомлений приведено в [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
