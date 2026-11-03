# Nezabudka public CI mirror contract

Status: permanent.

`obermot/Nezabudka` is the private canonical source of CURRENT. `obermot/alfa` is the public execution surface for transferable heavy Gradle/unit-test/APK checks; it is not a second source of truth.

## Mirror-owned public paths

Only these canonical paths may be replaced by the mirror:

- `build.gradle.kts`
- `settings.gradle.kts`
- `gradle.properties`
- `app/build.gradle.kts`
- `app/src/main/**`
- `app/src/test/**`
- generated `PUBLIC_MIRROR_PROVENANCE.txt`

Historical/public alfa documentation, `ci/BUILD_REQUEST`, and `.github/**` are not deleted by synchronization.

## Never publish from private Nezabudka

Do not mirror `docs/**`, handoff/status material, `.github/**`, `ci/**`, credentials, tokens, secrets, signing keys, `*.keystore`, `*.jks`, `*.p12`, `*.pfx`, `*.pem`, or `*.key`. Signing material is restored only at CI runtime from the existing alfa repository secret and is never committed.

## Traceability

Every synchronized snapshot must contain `PUBLIC_MIRROR_PROVENANCE.txt` with the exact canonical Nezabudka `source_sha`, a deterministic content digest, and mirrored file count. Public CI triggered by the private synchronizer verifies that the dispatch SHA equals the provenance SHA before testing/building.

The authoritative allowlist and fail-closed exporter live in private Nezabudka as `ci/PUBLIC_MIRROR_ALLOWLIST.txt` and `ci/export_public_mirror.py`. The private workflow `.github/workflows/sync-public-ci-mirror.yml` performs the controlled export, updates only mirror-owned paths here, and then dispatches this repository's heavy CI.

Cross-repository write access must use a dedicated fine-grained credential/GitHub App restricted to `obermot/alfa`; the ordinary Nezabudka `GITHUB_TOKEN` is repository-scoped and must not be treated as cross-repository authority.
