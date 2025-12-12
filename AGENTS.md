# Repository Guidelines

## Project Structure & Module Organization
- `app/` Android app; `src/main/java/com/antisocial/giftcardchecker/` holds `MainActivity`, `ScannerActivity`, `BalanceCheckActivity`, and market implementations under `markets/`.
- `app/src/main/res/` UI layouts, themes, and overlay drawables.
- `app/src/main/assets/js/` retailer-specific form fill/submit/balance scripts; `assets/models/` holds bundled ONNX CAPTCHA model.
- Tests live in `app/src/test` (unit/Robolectric) and `app/src/androidTest` (instrumented, Hilt-enabled).
- Python CAPTCHA helpers: `test_captcha_models.py`, `run_tests.sh`, `TestCaptchas/`, and downloaded models in `captcha_models/`.
- Additional docs in `documentation/`; helper scripts (logs/tests/keystore) sit in the repo root.

## Build, Test, and Development Commands
- `./gradlew assembleDebug` builds the debug APK to `app/build/outputs/apk/debug/`.
- `./gradlew installDebug` installs the debug build on a connected device/emulator.
- `./gradlew assembleRelease` builds the release APK (requires `app/keystore.properties`; see `setup_keystore.sh` or the `.template`).
- `./gradlew testDebugUnitTest` runs JVM/Robolectric unit tests; `./gradlew connectedAndroidTest` runs instrumented tests on a device/emulator.
- CAPTCHA OCR tests: `./setup_test_env.sh && ./run_tests.sh` (downloads models to `captcha_models/`); manual option `python test_captcha_models.py --no-download`.

## Coding Style & Naming Conventions
- Kotlin/Android style: 4-space indent, favor null-safety, use ViewBinding instead of `findViewById`, keep Hilt injections scoped to activities/fragments.
- Classes in PascalCase, functions/variables in camelCase, constants `UPPER_SNAKE`, package names lowercase.
- Resource names: layouts `activity_*`/`fragment_*`, drawables `ic_*`/`bg_*`, strings prefixed by feature (`market_rewe_*`, `scanner_*`).
- Keep market-specific selectors and scripts inside `markets/` and `assets/js/`; add short comments when flow or coordinate math is non-obvious.

## Testing Guidelines
- Cover new logic with unit tests in `app/src/test`; prefer Robolectric when Android APIs are involved.
- For WebView/Camera/Hilt flows, add instrumented cases in `app/src/androidTest` and run `connectedAndroidTest`.
- CAPTCHA/OCR changes: refresh fixtures in `TestCaptchas/` and rerun the Python suite; commit updated assets when behavior changes.

## Commit & Pull Request Guidelines
- Follow existing history: `Feature: ...`, `Fix: ...`, `Chore: ...` with imperative subject.
- Keep commits focused; include rationale in the body when changing network selectors, models, or build scripts.
- PRs should describe user impact, steps to verify (Gradle/Python commands run), and link issues.
- Include before/after screenshots for UI updates and note any new assets or permissions.

## Security & Configuration Tips
- Do not commit secrets; keep `app/keystore.properties` local and derive it from the `.template`.
- Validate redistribution rights before bundling new ONNX models into `app/src/main/assets/models/`.
- Review retailer selectors in `assets/js/` after website changes; keep network requests scoped to intended forms.
