# F-Droid candidate recipe (AndChamber)

This directory contains candidate metadata for an initial `v0.0.8` submission. It is not an F-Droid approval, inclusion, or reproducibility claim. Consult the upstream merge request and official build logs for the current inclusion status.

## Upstream merge request and CI status (2026-10-20)

- Official draft merge request: <https://gitlab.com/fdroid/fdroiddata/-/merge_requests/53432>. It contains only the two metadata files and asks the maintainers for help with a FOSS namespace, as the official template describes. It is not ready, accepted, or approved.
- The first hosted CI attempt on the fork failed on the `AutoUpdateMode: Version v%v` schema, formatter layout, and missing native host tools. The corrected recipe's fork and merge-request pipelines cannot create jobs because GitLab requires user verification, so its managed build-environment provisioning has not yet run.
- This directory's metadata now matches the corrected candidate: a full 40-character commit selector (`4d57d233fc8c8379c28c359f82bae71b39dc6be0`), plain `AutoUpdateMode: Version`, and a `sudo` block that installs `make`, `patch` and `gawk` on the F-Droid managed VM (`apt-get update`, `apt-get install -y make patch gawk`). `init` and `UpdateCheckData` are folded in the official formatter's layout.
- The current CI-compatibility gate has not passed yet. Do not describe the candidate as built, scanned or reviewed by F-Droid until the merge-request pipeline shows a pass.

## Files

- `metadata/io.andchamber.app.yml`: candidate build recipe.
- `metadata/io.andchamber.app/en-US/antifeatures/NonFreeNet.txt`: localized AntiFeatures text. Copy this file into the fdroiddata tree together with the YAML. The YAML alone does not carry the AntiFeature.

## Validation status

The table records historical local validation before the hosted CI compatibility fixes. It includes initial fixtures and a later clean checkout of the published `v0.0.8` tag. These host results do not verify the corrected recipe's managed provisioning or establish that hosted CI will pass.

| Check (historical) | Result |
|---|---|
| `fdroid lint io.andchamber.app` (official categories and AntiFeatures config) | exit 0 |
| `fdroid rewritemeta -l io.andchamber.app` | exit 0 |
| `fdroid build --test --stop --scan-binary io.andchamber.app:8` | exit 0, scanner 0 errors, binary scan passed |
| `fdroid checkupdates io.andchamber.app` (v0.0.8 tag) | exit 0, resolves `v0.0.8` to 0.0.8 / 8 |
| `fdroid checkupdates --auto` (synthetic v0.0.9 fixture) | exit 0, proposes 0.0.9 / 9 and a new build record |
| `App.AntiFeatures` from `read_metadata` on this directory | contains `NonFreeNet` |

The corrected file is byte-identical to the submission packet checked against the official metadata schema and the CI formatter at fdroidserver commit `0c3166f227d5917c913fa0d66a1ecab3bc7362b8`. Schema, lint, formatter fixed-point and version-detection checks passed locally. Stable fdroidserver 2.4.5 checks alone are not proof of compatibility with that CI revision. Hosted provisioning and build results remain pending.

The local build environment was a single Linux host: JDK 17, Android SDK with build-tools 35.0.0, NDK 29.0.14206865, and the repo's Gradle 8.9 wrapper distribution. The official build server uses different tooling, SDK paths, and NDK installation, so these results do not prove what the official server will produce.

## Source scan rationale (`scandelete`)

fdroidserver's scanner flags every `.gz` file in the build tree as an archive. The `init` step downloads three pinned upstream source archives into `build/runtime-sources/downloads/`. Their contents are extracted into `build/runtime-sources/{proot,talloc,libandroid-shmem}`, and the offline native build reads only those extracted trees. The recipe deletes exactly these three archives, by exact path, after they have been verified and extracted:

- `build/runtime-sources/downloads/talloc-2.5.0.tar.gz`
- `build/runtime-sources/downloads/proot-5.1.107.97.tar.gz`
- `build/runtime-sources/downloads/libandroid-shmem-0.7.tar.gz`

This is cache cleanup, not binary hiding. The extracted source trees, the prepare and build scripts, the patches, the lock file, and `sources.json` are kept. No wildcard, directory, or scanner exception is used. `--skip-scan`, `scanignore`, and forced builds are not used.

The scanner also removes `gradle/wrapper/gradle-wrapper.jar` by its own built-in rule. That is standard fdroidserver behavior, not something this recipe configures.

## Source tarball and local artifacts

The source tarball that fdroidserver generates for a local test build contains files written by its build preparation, including `local.properties` files with a host SDK path. Those generated files are not part of the upstream repository. Do not publish any tarball, log, config, or source copy produced by local fixture validation. Submit the metadata; the official build service must generate and check its own source archive.

## Version handling

- The version is defined once, in the root `gradle.properties` (`andchamber.versionName`, `andchamber.versionCode`). The recipe carries its own `versionName`/`versionCode` for the build entry, and `UpdateCheckData` reads both values from `gradle.properties` with two regexes, so auto-update compares against the source file.
- The auto-updater checks tags matching `^v[0-9]+\.[0-9]+\.[0-9]+$`. `AutoUpdateMode` is plain `Version`; the earlier `Version v%v` form was rejected by the official schema.
- The recipe keeps only `andchamber.runtimeSourcesDir=build/runtime-sources` as a Gradle property. `commit` is the full 40-character SHA of the published `v0.0.8` commit, not the tag name.

## Signing and distribution

- The proposed F-Droid distribution uses F-Droid's own signing key, separate from the GitHub release key. The candidate therefore has no `Binaries`, no `AllowedAPKSigningKeys`, and no reproducible-build claim. Local validation does not generate or access the official signing key.
- With this independent-key choice, the two distributions cannot directly update each other. Switching channels or changing keys requires reinstallation and careful data migration.

## Maintainer review items

- Native source pins, the NDK version, `scandelete` paths, and the `gradle.properties` version keys need review when they change.
- The `NonFreeNet` classification is conservative and pending upstream review. The text describes an optional choice of providers, not a commercial requirement. It is not a policy verdict.
- The public `Changelog` URL points to the project's release list rather than a fixed version's notes.

## Commands

Run these checks from an isolated metadata workspace with the official repository configuration and both metadata files. The workspace must be a Git repository. Before publication, a local fixture may replace `Repo` in that workspace's copy only; after publishing the tag, use the public recipe without that override.

```sh
# Configure ANDROID_HOME and fdroidserver's SDK/NDK paths for this workspace first.
export PATH="$ANDROID_HOME/build-tools/35.0.0:$PATH"
fdroid lint io.andchamber.app
fdroid rewritemeta -l io.andchamber.app
fdroid build --test --stop --scan-binary io.andchamber.app:8
fdroid checkupdates io.andchamber.app
```

Do not use `--on-server`, `--server`, `--force`, or `--skip-scan` locally. `--on-server` runs `sudo` steps that can lock the root account and remove `sudo` on the host. Do not run `fdroid init`, `publish`, or sign commands from this repository.

## Upstream submission route

The official quick start is at <https://f-droid.org/docs/Submitting_to_F-Droid_Quick_Start_Guide/>. Submit a merge request to the fdroiddata repository on GitLab with both metadata files (the YAML and the `antifeatures/` directory). Keep screenshots in the app's source repository, not fdroiddata. Authentication belongs in the official login tools, never in metadata, issue text, or chat.

Before submitting: publish the `v0.0.8` tag from the real project, confirm the candidate recipe resolves that tag, re-run the local checks against the published tag, and get maintainer review for the items above.
