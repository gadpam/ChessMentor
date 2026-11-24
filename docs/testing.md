# Проверка ChessMentor через GitHub, автотесты и на телефоне

[Быстрый старт и установка](../README.md) · [Карта интерфейса](android-ui-inventory.md) · [Целостность данных и разбор](integrity-and-analysis-fixes.md)

## Как выбрать сборку и прочитать проверки в GitHub Actions

В [Android CI](../.github/workflows/android-ci.yml) проверяйте **ветку и SHA коммита**. Для полного прогона с учётом эмулятора используйте ручной запуск: **Actions → Android CI → Run workflow → нужная ветка**. Обычный `push` (на `master` и `arena/**`) / `pull_request` и `workflow_dispatch` / релизный тег `v*` отличаются набором джобов:

| Задание (job) / артефакт | Когда запускается | Что подтверждает | Чего не подтверждает |
|---|---|---|---|
| **Unit Tests, Room Schema & Android Lint** (`test-and-lint`) → `test-lint-and-room-schema` | На каждый `push`, PR, `workflow_dispatch`, тег `v*` | Python preflight-валидацию CI/XML/CMake/AppSec ([ci_preflight.py](../.github/scripts/ci_preflight.py)) и тесты правил версионирования (`release_version.py`), все 31 файл JVM-тестов (`testDebugUnitTest`), неизменность схемы Room v5 (`5.json`), `lintDebug`, компиляцию `compileDebugAndroidTestKotlin` | Работу на реальном Android/KVM, вёрстку на экране телефона |
| **Build Debug & R8 Release APKs (Stockfish NDK)** (`build-apks`) → `chessmentor-apks` + `chessmentor-release-symbols` (90 дн.) | На каждый `push`, PR, `workflow_dispatch`, тег `v*` | Компиляцию Stockfish `sf_19` (C++17 NDK `arm64-v8a` + `x86_64`), сборку Debug и R8-минифицированного Release APK (`mapping.txt` + `native-debug-symbols.zip`), 16 КБ выравнивание ELF (`llvm-readelf`) на `libstockfish-lib.so` | Что релиз подписан постоянным ключом или опубликован |
| **Verify release signing with a disposable key** (`signing-smoke`) | После `build-apks` | Полный цикл [`.github/scripts/sign-apk.sh`](../.github/scripts/sign-apk.sh) на временном JKS (`zipalign -P 16`, `apksigner`, сверка сертификата DER, `aapt2` package/version, `.sha256`) | Наличие постоянных секретов или возможность обновить уже установленный release |
| **Instrumentation & Room Device Tests** (`room-device-test`) → `android-device-test-results` | На `pull_request` в `master`, `workflow_dispatch` (выбор API `29`/`34`/`35`) и релизные теги `v*` (на обычном `push` пропускается) | Весь `:app:connectedDebugAndroidTest` (29 тестовых файлов) на эмуляторе `x86_64` с предзакэшированной моделью NNUE: реальная БД Room и транзакции, нативный поиск и `stop()` Stockfish, Compose UI и навигация | Тест `ChessComImportTest` подставляет фиктивный JSON — живую сеть Chess.com и поведение на физическом экране нужно проверять вручную |

Актуальный статус и детали для любого коммита смотрите в разделе Checks / Actions конкретного запуска. В песочнице без установленного Android SDK все проверки подтверждаются прогонами `android-ci.yml`.

---

## Полный реестр автотестов

### 1. JVM-тесты домена, БД-утилит, безопасности и CI-скриптов (не требуют Android)

Запуск локально: `bash gradlew :app:testDebugUnitTest` и `python3 -m unittest discover -s .github/scripts`.

| Область | Файл теста | Что проверяет |
|---|---|---|
| **Анализ и PGN** | [GameMoveClassifierTest](../app/src/test/java/com/example/chessmentor/domain/analysis/GameMoveClassifierTest.kt) | Симметрия Win%, технические упрощения без ложных зевков, безнадёжные позиции, пороги качеств, переходы матов, жертвы (`BRILLIANT` — греческий дар / мат Легаля vs обычный размен), `GREAT_MOVE`, `BOOK`, сводки сторон и монотонные фазы партии |
| | [GameReviewHelperTest](../app/src/test/java/com/example/chessmentor/domain/analysis/GameReviewHelperTest.kt) | Фильтрация и навигация ключевых моментов, проверка повторных попыток и подсказок с превращениями, построение шагов ветки/часов/материала, отчёт тренера, разбор ответа на ошибку соперника |
| | [BoardViewModelRetryTest](../app/src/test/java/com/example/chessmentor/presentation/viewmodel/BoardViewModelRetryTest.kt) | Позиция и оценка после зевка соперника в режиме «Найти ответ», блокировка при отсутствии проверенного продолжения |
| | [SemanticMistakeAnalyzerTest](../app/src/test/java/com/example/chessmentor/domain/analysis/SemanticMistakeAnalyzerTest.kt) | Распознавание вилок, матовых угроз и упущенной выигрывающей тактики |
| | [AnalyzedMoveAndScoreTest](../app/src/test/java/com/example/chessmentor/domain/entity/AnalyzedMoveAndScoreTest.kt) | Матовые оценки и locale-safe форматирование, перспектива белых/чёрных в `AnalyzedMove`, `toMistake`, уникальность тем, `toKeyMoment`, `BOOK` только при совпадении с деревом |
| | [OpeningDatabaseTest](../app/src/test/java/com/example/chessmentor/domain/opening/OpeningDatabaseTest.kt) | 28 дебютов: пропуск стартовой позиции, точное совпадение FEN (Сицилианская, Итальянская), поиск по ECO |
| | [ChessMoveUtilsTest](../app/src/test/java/com/example/chessmentor/domain/util/ChessMoveUtilsTest.kt) | Конвертация UCI/SAN (включая рокировки и превращения), `isPawnPromotion`, `normalizePgn` (варианты, комментарии, FEN), отбрасывание нелегальных ходов |
| | [PgnParserAndUploadTest](../app/src/test/java/com/example/chessmentor/domain/pgn/PgnParserAndUploadTest.kt) | Заголовки Lichess, часы и цейтнот, отклонение нелегальных ходов и вариантов, `parseTimeControl`, `detectPlayerColor`, пакетная загрузка с дедупликацией, многотеговые строки, сохранение исходного названия дебюта в `searchBlob` при локализации и игнорирование отдельного токена `e.p.` |
| | [PgnArchiveExporterTest](../app/src/test/java/com/example/chessmentor/domain/pgn/PgnArchiveExporterTest.kt) | Пустой архив, партия без заголовков, сохранение тегов, невалидный `Result`, экранирование кавычек в тегах, `;`-комментарий перед результатом, `½-½` → `1/2-1/2`, имена файлов по локальной дате |
| **Сценарии анализа** | [AnalysisProgressBusyStateTest](../app/src/test/java/com/example/chessmentor/domain/usecase/AnalysisProgressBusyStateTest.kt) | Контракт `AnalysisProgress.isEngineBusy`: активные фазы блокируют движок, терминальные (`Completed`/`Failed`/`null`) освобождают |
| | [EngineFailureMidGameTest](../app/src/test/java/com/example/chessmentor/domain/usecase/EngineFailureMidGameTest.kt) | Прерывание разбора без частичного сохранения при сбое движка посередине партии; сохранение `COMPLETED` при ранней отмене `forceReanalyze`; транзакционный откат при сбое записи во время `forceReanalyze` |
| **Тренировка и SM-2** | [CuratedExerciseBankTest](../app/src/test/java/com/example/chessmentor/domain/training/CuratedExerciseBankTest.kt) | Уникальность ID/FEN и нечётные легальные решения у всех 46 задач, покрытие тем и сторон, превращение `d8=N+`, `getDailyPuzzle` |
| | [ExerciseClustererTest](../app/src/test/java/com/example/chessmentor/domain/clustering/ExerciseClustererTest.kt) | Классификация шахующих взятий/вилок, иммунитет доски вызывающего, шкала сложности |
| | [InterleavedPatternQueueTest](../app/src/test/java/com/example/chessmentor/domain/training/InterleavedPatternQueueTest.kt) | Очередь чередования паттернов, счётчики вмешательств и очистка сессии |
| | [PuzzleQualityEvaluatorTest](../app/src/test/java/com/example/chessmentor/domain/training/PuzzleQualityEvaluatorTest.kt) | Разделение выигрывающей тактики, защиты и нечёткой позиции; quiescence-обрезка PV; отсев позиций с несколькими равноценными ходами |
| | [SolutionReviewTest](../app/src/test/java/com/example/chessmentor/domain/training/SolutionReviewTest.kt) | Проверка перехода хода-триггера соперника, пошаговый разбор, объяснения после решения и нейтральный промпт без спойлера |
| | [SpacedRepetitionSchedulerTest](../app/src/test/java/com/example/chessmentor/domain/training/SpacedRepetitionSchedulerTest.kt) | `AttemptMetadata` (`@`-теги), группировка подходов в 10-мин окне, 4 ч для дорешивания, 10 мин охлаждение для провала/раскрытия, 1 дн. при подсказке/медленном решении, кривая коробок 0..5 |
| | [TrainingMoveVerifierTest](../app/src/test/java/com/example/chessmentor/domain/training/TrainingMoveVerifierTest.kt) | `Unavailable` не трактуется как ошибка или равенство; перспектива оценки соперника; единая глубина `EngineAnalysisProfile.MOVE_VERIFICATION_DEPTH`; проброс `CancellationException` |
| | [TrainingRatingCalculatorTest](../app/src/test/java/com/example/chessmentor/domain/training/TrainingRatingCalculatorTest.kt) | Эло-шкала, симметричный `K` (`40`/`28`/`22`), защита от фарма с подсказками и повторными ошибками, нечётный PV, `computePatternMastery`, детектор импульсивности, `TrainingMode.routeKey` |
| | [TrainingSessionPlannerTest](../app/src/test/java/com/example/chessmentor/domain/training/TrainingSessionPlannerTest.kt) | 4-фазная дуга 10-задачной сессии, спокойный пост-фидбек и итоговая сводка сессии |
| | [TrainingUseCasesTest](../app/src/test/java/com/example/chessmentor/domain/training/TrainingUseCasesTest.kt) | Изоляция пользователей, `Daily`, интервальный повтор, режимы тем, генерация из одной партии с фокусом хода, `SmartSession` и когнитивные режимы |
| | [TrainingUiStateContractTest](../app/src/test/java/com/example/chessmentor/presentation/viewmodel/TrainingUiStateContractTest.kt) | Состояние по умолчанию `TrainingUiState` и round-trip ключей маршрутов |
| **Статистика** | [PlayerAnalyticsCalculatorTest](../app/src/test/java/com/example/chessmentor/domain/statistics/PlayerAnalyticsCalculatorTest.kt) | Размер выборки точности, WDL и очки по цветам, дебютный репертуар (с учётом перспективы чёрных), фазы, цейтнот по часам, фильтры периодов и `GetUserStatisticsUseCase` |
| **Безопасность, бэкапы, движок, БД** | [PasswordHasherTest](../app/src/test/java/com/example/chessmentor/domain/security/PasswordHasherTest.kt) | PBKDF2-HMAC-SHA256 (`310_000` итераций), случайная соль, формат, отклонение битых строк, проверка legacy `hashed_` |
| | [SimpleJsonTest](../app/src/test/java/com/example/chessmentor/domain/backup/SimpleJsonTest.kt) | Строгость JSON-синтаксиса, Unicode, лимит вложенности, точность `Long`/`Int` без переполнения и округления |
| | [UserProgressBackupTest](../app/src/test/java/com/example/chessmentor/domain/backup/UserProgressBackupTest.kt) | Кодек бэкапа, отсев изменённого SHA-256 и неизвестной версии, идемпотентный импорт, обновление `PENDING` → `COMPLETED`, сброс `IN_PROGRESS` → `PENDING` |
| | [SessionAuthAndBackupTest](../app/src/test/java/com/example/chessmentor/domain/session/SessionAuthAndBackupTest.kt) | `AppThemeMode`, регистрация/вход с нормализацией email и апгрейдом legacy-хеша, смена пароля, экспорт PGN |
| | [EngineStatusAndNnueTest](../app/src/test/java/com/example/chessmentor/domain/engine/EngineStatusAndNnueTest.kt) | Имена файлов NNUE, потоковый SHA-256, валидация размера, проверка свободного места, классификация сетевых/дисковых ошибок |
| | [TypeConvertersTest](../app/src/test/java/com/example/chessmentor/data/local/TypeConvertersTest.kt) | Round-trip списков строк с запятыми/кавычками, fallback на legacy-формат и неизвестные enum |
| | [ExampleUnitTest](../app/src/test/java/com/example/chessmentor/ExampleUnitTest.kt) | Базовый шаблонный тест модуля |
| **CI-скрипты** | [test_release_version.py](../.github/scripts/test_release_version.py) · [ci_preflight.py](../.github/scripts/ci_preflight.py) · [test_ci_preflight.py](../.github/scripts/test_ci_preflight.py) | Проверка правил парсинга тегов `vX.Y.Z[-rc.N]`, вычисления `versionCode` и статический DevOps preflight (SHA-пиннинг CI, `gradlew`/`.githooks/pre-push` `+x`, `distributionSha256Sum`, TOML-зависимости, CMake/16KB, AppSec XML + изоляция `TesterProfiles`/`DebugEnvironment`, Room v5, `.gitattributes` и гигиена Git) |

---

### 2. Инструментальные и Room-тесты на Android (`app/src/androidTest`)

Запуск локально: `bash gradlew :app:connectedDebugAndroidTest` (в CI: `Instrumentation & Room Device Tests` на API 29 `x86_64`).

| Группа | Файл теста | Что проверяет |
|---|---|---|
| **Движок и БД на устройстве** | [StockfishSearchTest](../app/src/androidTest/java/com/example/chessmentor/data/engine/StockfishSearchTest.kt) | Реальная инициализация Stockfish + NNUE в Android-процессе, поиск лучшей линии и оценки, прерывание глубокого поиска через `stop()` (`null` вместо обрезанного результата) и последующий штатный поиск |
| | [BackupRestorePersistenceTest](../app/src/androidTest/java/com/example/chessmentor/data/local/BackupRestorePersistenceTest.kt) | Импорт JSON через реальные Room-репозитории и `RoomTransactionRunner`, повторное открытие БД, отклонение битого JSON / неверной контрольной суммы / висящей ссылки, транзакционный откат при сбое записи, выборка > 999 `gameIds` в `RoomMistakeRepository` без переполнения SQLite bind-параметров |
| | [TesterProfilesTest](../app/src/androidTest/java/com/example/chessmentor/data/local/TesterProfilesTest.kt) | Создание тестового профиля в debug, повторное переиспользование, изоляция от обычных учётных записей и идемпотентное заполнение демо-библиотеки (`seedSampleLibraryIfEmpty`) |
| | [ExampleInstrumentedTest](../app/src/androidTest/java/com/example/chessmentor/ExampleInstrumentedTest.kt) | Проверка целевого контекста `com.example.chessmentor` |
| **ViewModel-интеграция** | [InterruptedGameAnalysisTest](../app/src/androidTest/java/com/example/chessmentor/presentation/viewmodel/InterruptedGameAnalysisTest.kt) | Восстановление незавершённой партии новым владельцем ViewModel без несанкционированного перезапуска анализа |
| | [TrainingRetryTest](../app/src/androidTest/java/com/example/chessmentor/presentation/viewmodel/TrainingRetryTest.kt) | Повторная проверка хода при `checkUnavailable`, сохранение попытки и ходов при переходе к следующей задаче, маршрутизация всех обращений к движку через `verificationEngine` (продление, разбор/fallback ошибки, альтернативное продолжение, свободный анализ), откат при повреждённом ответном ходе без ложного решения, сохранение паузы после входной 450-мс анимации, очистка transient-флагов и сброс `pendingCheck` при `onRevealSolution()` |
| **UI и навигация (Compose)** | [ChessComImportTest](../app/src/androidTest/java/com/example/chessmentor/presentation/ui/ChessComImportTest.kt) | Фильтрация 61 архивной записи к 10 свежим стандартным партиям, определение стороны, локальные дубликаты и явное подтверждение импорта (на фиктивном JSON) |
| | [PgnPreviewTest](../app/src/androidTest/java/com/example/chessmentor/presentation/ui/PgnPreviewTest.kt) | Сброс превью/ника/контроля при замене PGN, переключение на архив, открытие наименьшего ID дубликата и подтверждение создания отдельной копии |
| | [LibrarySearchTest](../app/src/androidTest/java/com/example/chessmentor/presentation/ui/LibrarySearchTest.kt) | Комбинация поиска и фильтров, сохранение сортировки при сбросе, статус «В очереди», пустая библиотека, отмена и подтверждение удаления по конкретному ID записи |
| | [LibraryAnalysisTest](../app/src/androidTest/java/com/example/chessmentor/presentation/ui/LibraryAnalysisTest.kt) | Ограничение длинной ошибки с доступной «Закрыть» при крупном шрифте, привязка старта/повтора/отмены/перехода в сводку к конкретному ID партии |
| | [RecentGameCardTest](../app/src/androidTest/java/com/example/chessmentor/presentation/ui/RecentGameCardTest.kt) | Открытие конкретной записи при одинаковых соперниках, различие `null` и `0%` точности, неизвестного исхода и ничьей |
| | [GameAccuracyTest](../app/src/androidTest/java/com/example/chessmentor/presentation/ui/GameAccuracyTest.kt) | Согласованное отображение `null` / `0%` / `83.8%` на главной, в библиотеке и в сводке |
| | [GraphMovePickerTest](../app/src/androidTest/java/com/example/chessmentor/presentation/ui/GraphMovePickerTest.kt) | Открытие точного полухода без трактовки стартовой оценки как хода, сохранение фильтра и раскрытия списка после перехода к моменту |
| | [MistakeDisclosureTest](../app/src/androidTest/java/com/example/chessmentor/presentation/ui/MistakeDisclosureTest.kt) | Скрытие лучшего SAN и комментария до явного раскрытия на `CompactMistakeCard` и `AnalyzedMoveInfoCard` (320 dp, шрифт 150%) |
| | [ReviewNavigationTest](../app/src/androidTest/java/com/example/chessmentor/presentation/ui/ReviewNavigationTest.kt) | Раздельные действия шага по полуходам и ключевых моментов, отключение недоступных переходов в начале партии и закрытие меню |
| | [CompactReviewTest](../app/src/androidTest/java/com/example/chessmentor/presentation/ui/CompactReviewTest.kt) | Чтение 4 пунктов нижней панели на узком экране с крупным шрифтом; раздельные состояния подсказки, раскрытия ответа и продолжения в `RetryMistakePanel` |
| | [TrainingEntryTest](../app/src/androidTest/java/com/example/chessmentor/presentation/ui/TrainingEntryTest.kt) | Вход в `Daily` по мини-доске/кнопке до и после решения, независимость от `SmartSession`, выбор темы в `ModePicker` |
| | [TrainingFeedbackTest](../app/src/androidTest/java/com/example/chessmentor/presentation/ui/TrainingFeedbackTest.kt) | Видимость действия повтора до раскрытия подробностей; хранение линии и расписания внутри деталей |
| | [TrainingHelpTest](../app/src/androidTest/java/com/example/chessmentor/presentation/ui/TrainingHelpTest.kt) | Отображение влияния подсказок на рейтинг до применения, отмена без списания, блокировка повторного предложения показанного хода, подпись перехода для `Daily` |
| | [TrainingSafetyTest](../app/src/androidTest/java/com/example/chessmentor/presentation/ui/TrainingSafetyTest.kt) | Безопасный рендер `EmptyState` / `BrandEmptyState` без иконок и чистый выход при невалидном `userId` на `TrainingScreen` |
| | [StatisticsPeriodTest](../app/src/androidTest/java/com/example/chessmentor/presentation/ui/StatisticsPeriodTest.kt) | Выборка точности и отсутствие рейтинга тренировок в «Обзоре», предварительный рейтинг только в «Тактике», защита от показа устаревшего отчёта другого периода |
| | [ProfileHeaderTest](../app/src/androidTest/java/com/example/chessmentor/presentation/ui/ProfileHeaderTest.kt) | Полное имя для доступности и сохранённая кнопка настроек 48×48 dp при нике из 50 букв на 320 dp / 150% |
| | [ProfileSettingsNavigationTest](../app/src/androidTest/java/com/example/chessmentor/presentation/ui/ProfileSettingsNavigationTest.kt) | Возврат из настроек на главную через верхнюю панель и системный жест «Назад» в реальной `MainActivity` |
| | [PaletteContrastTest](../app/src/androidTest/java/com/example/chessmentor/presentation/ui/PaletteContrastTest.kt) | Проверка контрастности текста ≥ 4,5:1 на поверхностях, подложках и сплошных значках в светлой и тёмной темах |
| | [ResponsiveLayoutAndTargetTest](../app/src/androidTest/java/com/example/chessmentor/presentation/ui/ResponsiveLayoutAndTargetTest.kt) | Бейдж `9+` нижней панели, флип и семантика `EvaluationBar`, перенос и зоны касания ≥ 48 dp на 320 dp / 150% в `TrainingHelpControls`, `ReviewNavigationBar`, `NnueManagementCard`, `EvaluationGraph` и `KeyMomentsCard` |

---

## Короткий маршрут ручной приёмки на телефоне

Это сценарии проверки на физическом устройстве, а не утверждение, что каждый телефон уже проверен. Для удаления и восстановления берите тестовый профиль и партии, которые можно потерять.

| Где | Что сделать | Наблюдаемый результат |
|---|---|---|
| Первый старт и NNUE | Начать загрузку NNUE, повернуть экран. Позже в настройках движка поставить на паузу и повернуть экран. | Загрузка продолжается без потери прогресса; после явной паузы автозагрузка не возобновляется до нажатия «Повторить». |
| Главная | Нажать мини-доску задачи дня, вернуться, нажать «Начать тренировку» | Сначала открывается ежедневная задача, затем отдельная сессия из десяти. |
| PGN | Вставить одну партию, до импорта заменить её другой с иными именами и цветом | Старые имена и автоцвет исчезают; пока новый текст проверяется, импорта нет. Для архива с повтором число новых записей меньше общего числа блоков. |
| Chess.com, живая сеть | Загрузить свой публичный архив, отметить одну новую партию, подтвердить, снова открыть тот же месяц | Сторона и соперник соответствуют партии. Запись недоступна для повторного выбора; «Открыть партию №…» ведёт в локальную сводку. При ошибке сети библиотека не меняется. |
| Дубликат из файла | Открыть тот же PGN. Нажать «Создать отдельную копию» → «Отмена», затем «Открыть партию №…» | После отмены копии нет. Открывается существующая запись; «Назад» из сводки возвращает к импорту. Только «Создать и разобрать» разрешает отдельную копию. |
| Библиотека | Совместить имя соперника с неподходящим фильтром; нажать «Показать все партии». Вызвать удаление у одной из двух встреч и отменить. | Выдача отличает отсутствие совпадений от пустой базы; сброс не меняет сортировку. Номер в диалоге соответствует карточке, отмена оставляет все данные на месте. |
| Анализ и доска | Запустить разбор, отменить. После успешного разбора выбрать полуход из списка под графиком; сравнить стрелку вперёд и «К моменту». | После отмены нет автоперехода, а при повторном анализе (`forceReanalyze`) отмена/сбой сохраняет прежний разбор. В сводке ход открывает нужную позицию; стрелка — один полуход, «К моменту» — ключевой момент. |
| Тренировки и статистика | Из ошибки своей партии открыть тренажёр; сделать альтернативный ход без движка и нажать «Повторить» / «Решение»; в статистике сменить период | Упражнение относится к выбранной партии. Сбой оценки движка не засчитывает ошибку, а «Решение» убирает плашку повтора проверки. Период без партий не трактуется как 0% точности. |
| Настройки / доступность | Выбрать тему доски, вернуться к задаче. Увеличить шрифт до 150%+, включить TalkBack и открыть настройки из шапки. | Доска использует выбранную тему. Полное имя и рейтинг читаются, шестерёнка остаётся отдельным действием, поля смены пароля открываются независимо. |
| Резервная копия | Сохранить JSON через системный выбор файла. Восстановить в тестовый профиль, повторить импорт. Попробовать повреждённый JSON. | Сверить число партий и попыток, открыть восстановленный разбор. Повреждённый JSON не меняет БД и не меняет тему/настройки. Пароль и ник Chess.com не входят в JSON. |

## Как сообщить о сбое

В [GitHub Issues](https://github.com/gadpam/ChessMentor/issues) укажите SHA или ссылку на Actions, версию Android, модель телефона/эмулятора, размер шрифта и точный путь до действия. Приложите **ожидаемое и фактическое поведение**: например, «открыл №15 из архива, получил сводку №14». Для импорта достаточно минимального обезличенного PGN, для сетевой ошибки — текста сообщения и шага (ник / месяцы / партии / сохранение). Не публикуйте пароли, keystore или пользовательский JSON-бэкап.
