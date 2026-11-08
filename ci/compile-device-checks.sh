#!/usr/bin/env bash
set -euo pipefail
# A repository download timeout is not a Kotlin/test failure. Retry that case once,
# retaining both logs; never turn a failing test or compiler diagnostic into success.
for attempt in 1 2; do
    set +e
    gradle --no-daemon --max-workers=2 :app:lintDebug :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --stacktrace 2>&1 | tee "../evidence/compile-attempt-${attempt}.log"
    status=${PIPESTATUS[0]}
    set -e
    if [ "$status" -eq 0 ]; then exit 0; fi
    if [ "$attempt" -eq 1 ] && rg -q 'Could not (GET|HEAD)|SocketTimeoutException|Received status code 50[234]' "../evidence/compile-attempt-${attempt}.log"; then
        echo 'Retrying once after a dependency download/network failure.'
    else
        exit "$status"
    fi
done
exit 1
