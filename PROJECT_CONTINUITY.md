# Незабудка — постоянное состояние проекта и правила продолжения

Этот файл является долговечным источником контекста для следующих чатов, handoff-пакетов и исполнителей. Перед существенными изменениями его нужно читать вместе с `TEST_HISTORY.md`.

## 1. Канонический репозиторий

- Рабочий репозиторий: `obermot/alfa`.
- Репозиторий публичный.
- Старый `obermot/Nezabudka` не использовать как рабочую базу, но его историю можно использовать для recovery/regression-анализа.
- Не коммитить keystore, пароли, токены, секреты, приватные handoff-файлы и иные чувствительные материалы.

## 2. Текущее приложение

- Android applicationId: `com.nezabudka.alpha.clean013`.
- Текущая версия в `main`: `0.2.5-per-reminder-controls-alpha`, versionCode `115`.
- Базовый путь создания напоминаний восстановлен из проверенной линии 0.1.5 и подтверждён пользователем.
- APK тестируется на физическом Android-смартфоне.
- Фактическая полевая история версий и дефектов ведётся в `TEST_HISTORY.md`.

## 3. GitHub Actions — постоянное правило экономии

- Обычные коммиты в `main` не должны запускать сборку.
- Контрольная сборка вызывается через изменение `ci/BUILD_REQUEST` либо вручную через workflow dispatch.
- Связанные изменения пакетировать и запускать один осмысленный build.
- Не перезапускать workflow вслепую; сначала анализировать причину ошибки.
- Workflow: `.github/workflows/android-build.yml`.

## 4. Постоянная тестовая подпись APK

Введена постоянная тестовая подпись, чтобы новые APK устанавливались поверх предыдущих без удаления приложения.

Repository Secrets:

- `NEZABUDKA_KEYSTORE_B64`
- `NEZABUDKA_KEYSTORE_PASSWORD`
- `NEZABUDKA_KEY_ALIAS`
- `NEZABUDKA_KEY_PASSWORD`

Значения секретов нигде публично не хранить. Сам keystore не находится в репозитории; резервная копия хранится у владельца проекта. Подпись нельзя менять без отдельного осознанного решения.

## 5. Цикл доставки

1. Внести пакет изменений без запуска Actions.
2. Один раз изменить `ci/BUILD_REQUEST`.
3. Дождаться результата того же run.
4. Скачать artifact ZIP.
5. Извлечь из него настоящий APK.
6. Передать APK пользователю в чат.
7. Пользователь устанавливает поверх текущей версии и тестирует.

## 6. Open Source Search Gate

Перед нетривиальной новой возможностью сначала проверять зрелые open-source библиотеки / SDK / reference implementations. Сравнивать качество, лицензию, безопасность, приватность, Android/Kotlin-совместимость, вес зависимости, поддержку, офлайн-возможности и стоимость. Не подключать тяжёлую зависимость без необходимости.

Для перехода от глобального repeat к per-reminder repeat отдельная библиотека не требуется: используется существующая Room-модель и штатная явная миграция schema version 1→2.

## 7. Проверенная recovery-база

Последняя исторически подтверждённая рабочая база до регрессий: `0.1.5-clean-alpha` из старого `obermot/Nezabudka`, ветка `test-harness`.

- контрольная сборка: commit `6be2936f571cc11e1383f1693aa60c1ee9214755`;
- кодовое состояние перед build-trigger: `79711e23540dc733cc73b9b989c093f7204a61fd`;
- `MainActivity.kt` этой версии: blob `a9ff81894e81c96ff6faa8bf5f0df6426f50653a`.

В 0.1.9 пользовательское ядро было контролируемо восстановлено из этой версии. Инфраструктура public repo, постоянная подпись и workflow при этом сохранены.

## 8. Проверенные базовые свойства

Пользователь подтвердил на физическом устройстве:

- текстовые напоминания создаются и срабатывают;
- голосовые напоминания создаются;
- срабатывание работает при закрытом/смахнутом приложении;
- audio focus приглушает воспроизводимое видео во время речевого напоминания;
- имя сохраняется между обновлениями;
- `через минуту` работает;
- `через полминуты` исправлено как 30 секунд.

## 9. Alarm Screen

По решению пользователя введён отдельный видимый `AlarmActivity`. В будущем это расширяемый тревожный экран.

Текущая архитектура:

- notification использует `full-screen intent` на `AlarmActivity`;
- manifest содержит `USE_FULL_SCREEN_INTENT`;
- activity может показываться поверх lock screen и включать экран;
- если Android не разрешает автоматический full-screen запуск, системный heads-up notification остаётся первым сигналом, но больше не является единственным входом;
- ongoing notification остаётся в шторке до ACK/отмены и по нажатию открывает alarm-screen;
- в основном списке у уже сработавшего reminder есть `Открыть`, который также открывает alarm-screen;
- распознавание ACK работает только при видимой activity из-за Android while-in-use ограничений на микрофон;
- локальный Vosk используется как основной ACK-распознаватель, системный `SpeechRecognizer` — fallback;
- partial recognition не может подтвердить reminder;
- успешный ACK голосом или кнопкой сначала показывает `Подтверждено` примерно 1.2 секунды и только потом закрывает alarm-screen.

## 10. Повтор и snooze

С 0.2.5 repeat является свойством конкретного reminder:

- `ReminderEntity.repeatIntervalMinutes` хранит интервал этого напоминания;
- база Room поднята с version 1 до version 2 с явной migration 1→2 без destructive reset;
- при миграции существующие активные reminders получают ранее выбранное глобальное значение;
- отдельного глобального блока `Повтор` на основном экране больше нет;
- в каждой карточке показано её значение (`1 мин`, `10 мин`, `2 ч 30 мин`, `0 мин`); нажатие на значение открывает два NumberPicker: часы и минуты;
- изменение относится только к выбранному reminder;
- последнее выбранное значение сохраняется только как скрытый default для новых reminders;
- `0 ч 0 мин` означает отсутствие автоматического retry; сработавший reminder при этом остаётся активным до явного ACK/отмены;
- snooze в notification/alarm-screen использует собственный `repeatIntervalMinutes` reminder.

## 11. Имя пользователя

Желаемая логика:

- после сохранения поле имени остаётся видимым без курсора;
- кнопка `Изменить имя` неактивна, пока поле не получило фокус;
- касание поля/появление курсора активирует кнопку;
- касание вне поля снимает фокус, курсор исчезает, кнопка снова неактивна;
- несохранённое изменение при уходе фокуса откатывается к сохранённому имени;
- после сохранения нового имени фокус снимается.

## 12. Естественное время

Парсер не должен зависеть от обязательных ключевых слов там, где длительность однозначна.

С 0.2.5 должны пониматься как относительное время без обязательного `через`:

- `2 минуты`, `две минуты`, `15 минут`;
- `1 час`, `два часа`;
- `1 час 20 минут` и аналогичные сочетания.

Слово `через` по-прежнему допустимо, но не обязательно.

## 13. Общий командный вход

Кнопка `Говорить` и текстовое поле являются общим входом команд, а не только созданием нового reminder.

Поддерживаются:

- создать напоминание;
- показать/озвучить список активных напоминаний;
- отменить последнее напоминание;
- отменить конкретное напоминание по тексту/теме;
- отменить все активные напоминания с обязательным подтверждением;
- перенести последнее/сработавшее напоминание.

При неоднозначном совпадении конкретного reminder система должна просить уточнить формулировку, а не удалять случайный объект.

## 14. Звук срабатывания

- Вибрация перед reminder убрана через отдельный notification channel без vibration.
- Перед речью используется один заметный электронный alarm/chime tone, затем речь.
- После основного текста остаётся отдельная пауза перед вопросом `Вы меня услышали?`.
- Интонация системного TTS может зависеть от конкретного голосового движка Android.

## 15. Текущий тестовый пакет 0.2.5

Проверить на физическом устройстве:

- миграция старой базы проходит без потери reminders;
- отдельный глобальный repeat-блок исчез;
- нажатие на число интервала в конкретной карточке открывает колёсики и меняет только этот reminder;
- `0` прекращает автоматические retry, но сработавший reminder остаётся доступным для ACK;
- `две минуты`, `2 минуты`, `1 час`, `1 час 20 минут` работают без слова `через`;
- tap вне поля имени снимает курсор и деактивирует кнопку;
- после ACK видна надпись `Подтверждено`, после возврата одноразовый reminder исчезает из активного списка;
- после исчезновения heads-up alarm-screen можно открыть из ongoing notification в шторке и через `Открыть` в карточке сработавшего reminder.

## 16. Правило handoff

Каждый следующий handoff должен явно передавать:

- `obermot/alfa` как канонический публичный repo;
- обязательность чтения `PROJECT_CONTINUITY.md` и `TEST_HISTORY.md`;
- экономный GitHub Actions workflow;
- Open Source Search Gate;
- постоянную APK-подпись и имена четырёх Secrets, но никогда не их значения;
- запрет менять signing key;
- recovery-базу 0.1.5 / 0.1.9;
- архитектуру Alarm Screen;
- текущую версию 0.2.5;
- последний фактический тест пользователя и следующий незавершённый шаг.

Существенные новые решения и результаты тестов нельзя оставлять только в памяти чата — их нужно записывать сюда или в другой явно связанный долговечный проектный артефакт.

## Execution-turn continuity rule (2026-09-21)

- When the user says to continue an approved project task, do not end the turn with a mere intermediate status such as “continuing”.
- Use the entire available execution turn to advance the work through as many non-blocked steps as practical: inspect, edit, commit, build, read failures, fix, rebuild, and verify.
- Return to the user only with a concrete result or when a genuine user action / authorial decision is required.
- Routine engineering choices, intermediate states, compilation failures that can be diagnosed, and ordinary retries are not reasons to interrupt the user.
- Before each project action, re-apply this rule together with the permanent GitHub/Actions efficiency rule.

## Handoff-first / no-eye-balling rule (2026-09-21)

- For every continued project task, first re-read and follow the current handoff and its referenced canonical materials before making implementation or visual decisions.
- If the handoff provides a link, file, image, mockup, repository path, or other reference, use that reference directly whenever it is accessible; do not replace it with memory, approximation, reconstruction, or an independently invented interpretation.
- Never implement approved UI “by eye” from a prose summary when the canonical visual reference exists. The approved visual reference and handoff are the implementation source of truth, subject only to a later explicit user instruction.
- If a referenced source is genuinely inaccessible, do not guess from it: use the remaining canonical materials and obtain the missing source before making decisions that depend on it.
- Re-apply this rule before each project action together with the execution-turn continuity rule.
