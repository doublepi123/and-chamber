# app/src/main/java/io/andchamber/app/i18n/

## Responsibility

The single place that maps runtime keys (`MessageKey`, `InstallStep`) to Android string resource IDs, so the UI and native notifications show the same wording. `UserMessages.kt` is the only file.

## Design

`object UserMessages`:

- `headline(key: MessageKey): @StringRes Int`: exhaustive `when` over 22 `MessageKey` values:
  - Failure headlines: `MirrorsUnreachable`, `PackagesFailed`, `OpenChamberInstallFailed`, `NativeBuildFailed`, `PtySmokeFailed`, `PortInUse`, `OpenCodeStartFailed`, `ExitedDuringStart`, `NotListeningInTime`, `NotReadyInTime`, `KeptStopping`, `StoppedUnexpectedly`, `RestartFailed`, `EmbeddedProotMissing`, `Generic`.
  - Progress labels: `ProgressUnpacking`, `ProgressInstallingPackages`, `ProgressInstallingOpenChamber`, `ProgressBuildingNative`, `ProgressVerifying`, `ProgressActivating`, `ProgressDone`.
  - Resource names: `R.string.msg_*`.
- `failureText(context, failed: RuntimeState.Failed): String`: the localised headline when `messageKey` is set; otherwise `failed.reason` (English technical text); if blank, `msg_generic`. Never empty.
- `stepLabel(step: InstallStep): @StringRes Int`: exhaustive over the six install steps, `R.string.step_*` (`DownloadRootfs`, `ExtractRootfs`, `InstallPackages`, `InstallOpenChamber`, `BuildNativeModules`, `Verify`).

Design rules:

- Exhaustive `when` means adding a `MessageKey` or `InstallStep` value fails to compile until a string is mapped.
- The mapping returns `@StringRes Int`; resolution to text happens at the caller with a `Context`, except `failureText`.
- Raw `reason` text from the runtime is a fallback only. It is not localised.

Not covered here:

- Notification titles in `notify/RuntimeErrorWatch.kt` come from `RuntimeErrorWatch.Titles` (built in `di/AppModule.kt` from `R.string.alert_*`), not from this object.
- The notification channel names (`R.string.channel_*`) and the action labels (`R.string.action_*`) are read directly in `notify/`.

## Flow

1. Runtime state changes to `Failed(messageKey, reason)` (controller in `runtime/`).
2. Caller calls `UserMessages.failureText(context, state)`:
   1. `messageKey` non-null → `context.getString(headline(key))`.
   2. Else `reason` non-blank → `reason`.
   3. Else `context.getString(R.string.msg_generic)`.
3. Install progress: caller maps `InstallStep` with `stepLabel(step)` and calls `context.getString(...)`.
4. The same text is used on the Compose screens and in the foreground notification (`RuntimeService.buildNotification` → `label()` → `failureText`).

## Integration

- Imports `io.andchamber.app.R` (generated resources), `runtime.InstallStep`, `runtime.MessageKey`, `runtime.RuntimeState`.
- Consumers:
  - `service/RuntimeService.kt` (`RuntimeState.label()` → `failureText`; `setupText`).
  - `ui/` screens and components (through `UserMessages`; see `ui/codemap.md`).
- Source of the resource strings: `app/src/main/res/values*/strings.xml` (`msg_*`, `step_*`, `runtime_notification_*`).
- No HTTP, no DI, no state. Pure mapping object plus one `Context` call.
