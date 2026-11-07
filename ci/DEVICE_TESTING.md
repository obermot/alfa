# Free Android device regressions

This branch adds an independent emulator workflow. Canonical application development remains
in private `obermot/Nezabudka`, branch `assistant/remaining-integration-20261103`.
Do not use the historical app sources in alfa as the current application.

The owner authorized free public Actions on 2026-11-07. Private Actions remain restricted.
Neither repository's main branch needs changes for this workflow.

To request a justified run, update only `ci/DEVICE_TEST_REQUEST` on
`assistant/emulator-validation-20261107` with one full canonical `source_sha` and push.
Do not change `ci/BUILD_REQUEST`, `ci/INTEGRATION_BUILD_REQUEST`, or `ci/MIRROR_REQUEST`.
Do not trigger the source-publication/mirror workflow.

The runner checks out the private commit using the existing read credential, generates an
ephemeral signing key, compiles app/test APKs, and runs Android 15 instrumentation on KVM.
The app/test APKs and private source are not uploaded. Gradle cache is disabled.
Only synthetic test evidence, logs, screenshots and reports are uploaded, retained for 3 days.
These artifacts are public; never test with actual personal data in this workflow.

A green run establishes only the tested cases at the recorded SHA. It does not establish
Samsung/One UI compatibility, audible Russian speech, microphone capture, Doze reliability,
reboot stability, or full 88-page visual acceptance.
