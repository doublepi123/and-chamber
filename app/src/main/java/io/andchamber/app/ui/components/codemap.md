# app/src/main/java/io/andchamber/app/ui/components/

## Responsibility

Stateless, reusable Compose widgets shared by `home/`, `web/` and the launch cover. Contains no runtime or navigation logic; callers pass data and callbacks.

Files:
- `Components.kt`: `StatusTone` and `StatusTone.color()`, `StatusDot`, `StatusPill`, `SectionHeader`, `ChamberCard`, `DetailRow`, `ChamberMark` (Canvas cube logo), `ConfirmDialog`, `IconTile`.
- `LogConsole.kt`: `LogConsole`, a terminal-style, auto-following, selectable log tail with a copy button.

## Design

- Every visual token comes from the theme, never hard-coded: `ChamberTheme.colors` (card, cardBorder, success, warning, idle, terminal*) and `MaterialTheme.colorScheme`, `typography` and `shapes`. Text styles use `ChamberTextStyles` (mono `code`, `codeSmall`, `log`).
- `StatusTone` maps to a color (Idle, Busy, Live, Error). `StatusDot` animates: Busy pulses with an infinite transition, Live draws a steady halo (so a running server doesn't keep the screen animating), and Idle is flat.
- `ChamberCard` is a flat `Surface` with a 1 dp hairline border and the `large` shape. It becomes clickable when `onClick` is set, and an optional `accent` tints the border.
- `ConfirmDialog` is a two-button `AlertDialog`. `destructive = true` tints the confirm action with `colorScheme.error`. Its dismiss label defaults to `R.string.cancel`.
- `ChamberMark` draws the OpenChamber cube with `Canvas` on a 100x100 logical grid, which avoids bundling an image. It is scaled to the requested `size`.
- `LogConsole` tracks follow mode. Output is followed while the list is at its end, and following resumes when scrolling returns to the bottom. Line colors come from prefixes: `ERROR`/`FATAL`/`E/` use error, `WARN`/`W/` use warning, and `$ `/`> ` use primary. Empty lines render as a space so they keep their height. The list is wrapped in `SelectionContainer`.
- Strings are resolved with `stringResource` and `pluralStringResource` (`R.plurals.log_lines`), not passed as literals.

## Flow

Typical composition path (home card → component):
1. `LocalRuntimeCard` renders `CardHeader` with `StatusPill(text, tone)`, where `tone` comes from `RuntimePhase.tone()`. Busy phases (Installing, Starting, Stopping) use `StatusTone.Busy`, Running uses `Live`, Failed uses `Error`.
2. `LocalRuntimeCard` shows `LogConsole(lines = logTail)` when its log toggle is open. A failed phase opens the log automatically.
3. `RemoteServersSection` and `HomeScreen` place `ChamberCard` containers, with `SectionHeader` labels above them.
4. `ConfirmDialog` is shown by the card when Repair or Uninstall is pressed; `RemoveServerDialog` wraps it for server removal.
5. `ChamberMark` is used by `LaunchCover` (56 dp), the Home header (36 dp) and web panels (48 dp).

LogConsole internals:
1. `snapshotFlow { listState.isScrollInProgress }` filters for stop-of-scroll, then sets `follow = !canScrollForward`.
2. `LaunchedEffect(lines)` calls `scrollToItem(lastIndex)` only while `follow` is true.
3. The copy button passes `lines.joinToString("\n")` to `rememberCopyAction()`.

## Integration

- Consumed by `ui/home/` (`ChamberCard`, `ConfirmDialog`, `IconTile`, `DetailRow`, `LogConsole`, `StatusPill`, `StatusTone`, `SectionHeader`, `ChamberMark`) and `ui/web/` (`ChamberMark`, `StatusDot`, `StatusTone` in `EdgeHandle`). `ui/LaunchFlow.kt` uses `ChamberMark` for the cover.
- Depends on `ui/theme/` (`ChamberTheme`, `ChamberTextStyles`, `MaterialTheme`) and `ui/` helpers: `rememberCopyAction` (copy text, with a toast below Android 13) and `io.andchamber.app.ui.theme.ChamberTextStyles`.
- Resource dependency: `R.string.copy_log`, `R.string.log_empty`, `R.string.cancel`, and `R.plurals.log_lines`.
- No dependency on `io.andchamber.app.runtime`: `LogConsole` takes `List<String>`, and status is passed as `StatusTone`.
