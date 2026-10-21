# app/src/main/java/io/andchamber/app/ui/theme/

## Responsibility

Design tokens for the native shell: Material 3 color schemes, typography, shapes, and an extended `ChamberColors` set for values Material 3 has no slot for. Colors come from OpenChamber's web chrome so the native and web UIs read as one product.

Files:
- `Color.kt`: `DarkColors` and `LightColors` (Material `ColorScheme`), `ChamberColors`, `DarkChamberColors` and `LightChamberColors`.
- `Type.kt`: `ChamberTypography`, `MonoFamily`, `ChamberTextStyles` (`code`, `codeSmall`, `log`).
- `Theme.kt`: `AndChamberTheme`, `ChamberShapes`, `LocalChamberColors`, `ChamberTheme` accessor.

## Design

- Dark-first. `AndChamberTheme(darkTheme = isSystemInDarkTheme())` follows the system. Dynamic color is off on purpose.
- Palette: warm ink and amber. Dark background `#151313` with primary `#EDB449` (OpenChamber's accent). Light background `#FBF5EA` with primary `#8F5A00`.
- Dark and light are separate palettes, not inverted. Surface containers step from `surfaceContainerLowest` to `Highest` for depth without shadows.
- Tokens are `internal`; only `AndChamberTheme` is public. Components read `ChamberTheme.colors`, a `@ReadOnlyComposable` getter over `LocalChamberColors`.
- `ChamberColors` is `@Immutable`: `card`, `cardBorder`, `success`, `successContainer`, `onSuccessContainer`, `warning`, `idle`, and the `terminal*` set used by `LogConsole`.
- `LocalChamberColors` is a `staticCompositionLocalOf`, since the value changes only on a theme flip.
- Typography: `MonoFamily` (`FontFamily.Monospace`) covers display, headline, `labelMedium`, `labelSmall` and code styles. Body and title use sans. `labelSmall` has wide tracking for section headers.
- Shapes: `ChamberShapes` uses 6, 10, 14, 18 and 26 dp corners (extraSmall to extraLarge). Cards use `large`; rows and buttons use `medium`.

## Flow

1. `AndChamberApp` wraps all content in `AndChamberTheme {}` once.
2. `AndChamberTheme` picks `DarkChamberColors` or `LightChamberColors`, provides `LocalChamberColors`, and wraps `MaterialTheme(colorScheme, typography, shapes)`.
3. Components read `MaterialTheme.colorScheme.*` for Material roles and `ChamberTheme.colors.*` for extended roles.
4. `WebScreen` reads `MaterialTheme.colorScheme.background`, converts it with `toArgb()`, and passes it to `WebViewPool.obtain`. It also uses `isSystemInDarkTheme()` for the WebView theme (`prefers-color-scheme`). When either changes, `ChamberWebView.applyTheme` restyles the live WebView in place (no reload), since the activity handles `uiMode` itself and the pooled WebView outlives the flip.
5. `SystemBarsEffect` uses the background luminance to choose bar icons, not `darkTheme` directly.

## Integration

- Entry point: `AndChamberTheme`, called from `ui/AndChamberApp.kt`.
- Consumers: `ui/components/` (`Components.kt`, `LogConsole.kt`), `ui/home/` (cards and rows), `ui/web/` (`WebScreen`, `EdgeHandle`, `WebPanels`), and `ui/LaunchFlow.kt` (cover background).
- Related: `ui/SystemUi.kt` uses background luminance for bar icons. `ui/web/WebScreen.kt` passes the background to `ChamberWebView` as an ARGB int.
- No runtime dependencies. No `R.*` references.
