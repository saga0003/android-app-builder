# Calculator APK

A native Android calculator with a custom-drawn "Obsidian" dark UI, built without Android Studio.

## Features
- Addition, subtraction, multiplication and division (chained operations supported)
- Decimal numbers, percentage and sign toggle
- Thousands separators and an auto-shrinking display for long numbers
- Custom circular keys with ripple effects, press-scale animation and haptic feedback
- Active-operator highlighting (the selected operator key lights up)
- Long-press backspace to clear everything
- Edge-to-edge layout that respects system bar insets
- Adaptive launcher icon (vector-based, no image assets required)
- Divide-by-zero / math-error handling with recovery

## UI
The entire interface is programmatic (no XML layouts): a dark gradient backdrop,
circular keys drawn with `Canvas` + `RippleDrawable`, and diagonal gradient fills
on operator and equals keys. All APIs are framework-only — no external dependencies.

## Cloud build
GitHub Actions automatically builds a debug APK on every push to `main`.

Artifact name: `calculator-apk`

APK inside artifact: `app-debug.apk`
