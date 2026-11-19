#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
# Offline CI must never inherit a developer's explicit live school-test opt-in.
unset SCHEDULE_LIVE_PROBE SCHEDULE_TEST_ACCOUNT SCHEDULE_TEST_PASSWORD ZSLF_LIVE_TEST ZSLF_USERNAME ZSLF_PASSWORD
python3 -B scripts/verify-wrapper.py
python3 -B scripts/display_text_guard.py --root .
python3 -B scripts/architecture_guard.py --root .
bash ./gradlew --no-daemon --stacktrace \
  -PbetterZslfEdition=standard \
  verifyModuleCoordinates \
  :core:text:jvmTest \
  :core:run-model:jvmTest \
  :core:logging:jvmTest \
  :core:network-session:jvmTest \
  :core:storage:jvmTest \
  :core:storage:testAndroidHostTest \
  :platform:storage:jvmTest \
  :platform:location:jvmTest \
  :design-system:jvmTest \
  :feature:run:api:jvmTest \
  :feature:run:domain:jvmTest \
  :feature:run:data:jvmTest \
  :feature:run:presentation:jvmTest \
  :feature:run:ui:jvmTest \
  :feature:run:runtime:jvmTest \
  :feature:run:domain:testAndroidHostTest \
  :feature:run:data:testAndroidHostTest \
  :feature:run:presentation:testAndroidHostTest \
  :feature:run:ui:testAndroidHostTest \
  :feature:run:runtime:testAndroidHostTest \
  :feature:services:presentation:jvmTest \
  :feature:services:ui:jvmTest \
  :platform-ui:readonly-map:jvmTest \
  :feature:schedule:api:jvmTest \
  :feature:schedule:domain:jvmTest \
  :feature:schedule:data:jvmTest \
  :feature:schedule:runtime:jvmTest \
  :feature:schedule:runtime:testAndroidHostTest \
  :feature:schedule:ui:jvmTest \
  :feature:schedule:ui:testAndroidHostTest \
  :platform:schedule-native:jvmTest \
  :platform:schedule-native:testAndroidHostTest \
  :feature:home:presentation:jvmTest \
  :feature:home:ui:jvmTest \
  :feature:update:domain:jvmTest \
  :feature:update:data:jvmTest \
  :feature:update:presentation:jvmTest \
  :feature:update:ui:jvmTest \
  :feature:accountrecovery:domain:jvmTest \
  :feature:accountrecovery:data:jvmTest \
  :feature:accountrecovery:presentation:jvmTest \
  :feature:accountrecovery:ui:jvmTest \
  :feature:campusnet:domain:jvmTest \
  :feature:campusnet:data:jvmTest \
  :feature:campusnet:presentation:jvmTest \
  :feature:campusnet:ui:jvmTest \
  :feature:auth:domain:jvmTest \
  :feature:auth:data:jvmTest \
  :feature:auth:presentation:jvmTest \
  :feature:auth:ui:jvmTest \
  :feature:profile:domain:jvmTest \
  :feature:profile:data:jvmTest \
  :feature:profile:presentation:jvmTest \
  :feature:content:domain:jvmTest \
  :feature:content:data:jvmTest \
  :feature:content:presentation:jvmTest \
  :platform:updates:jvmTest \
  :platform:updates:testAndroidHostTest \
  :platform:location:testAndroidHostTest \
  :feature:accountrecovery:domain:testAndroidHostTest \
  :feature:accountrecovery:data:testAndroidHostTest \
  :feature:accountrecovery:presentation:testAndroidHostTest \
  :feature:accountrecovery:ui:testAndroidHostTest \
  :feature:campusnet:domain:testAndroidHostTest \
  :feature:campusnet:data:testAndroidHostTest \
  :feature:campusnet:presentation:testAndroidHostTest \
  :feature:campusnet:ui:testAndroidHostTest \
  :feature:auth:domain:testAndroidHostTest \
  :feature:auth:data:testAndroidHostTest \
  :feature:auth:presentation:testAndroidHostTest \
  :feature:auth:ui:testAndroidHostTest \
  :feature:profile:domain:testAndroidHostTest \
  :feature:profile:data:testAndroidHostTest \
  :feature:profile:presentation:testAndroidHostTest \
  :feature:content:domain:testAndroidHostTest \
  :feature:content:data:testAndroidHostTest \
  :feature:content:presentation:testAndroidHostTest \
  :shared:jvmTest \
  :shared:testAndroidHostTest \
  :platform:android-host:testDebugUnitTest \
  :platform:android-host:lintDebug \
  :androidApp:testDebugUnitTest \
  :androidApp:lintDebug \
  :androidApp:assembleDebug
