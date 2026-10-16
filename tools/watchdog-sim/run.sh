#!/usr/bin/env bash
# Runs the WG VPN watchdog logic against a simulated world (DNS, server power/IP, phone
# internet) on a plain JVM: no device or Gradle needed.
#
# Requirements:
#   KOTLINC      path to the Kotlin compiler home (contains bin/kotlinc and lib/)
#   ANDROID_JAR  path to an android.jar (e.g. $ANDROID_HOME/platforms/android-35/android.jar)
#   ORG_JSON_JAR path to an org.json jar (https://github.com/stleary/JSON-java)
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
app="$here/../../app/src/main/java/com/zaneschepke/wireguardautotunnel"
out="$(mktemp -d)"
real="$out/real"; mkdir -p "$real"
cp "$app/domain/enums/TunnelActionSource.kt" "$app/domain/enums/TunnelMode.kt" \
   "$app/domain/events/TunnelActionEvent.kt" "$app/domain/enums/NotificationAction.kt" \
   "$app/util/Constants.kt" "$app/util/network/PingStats.kt" "$real/"
# R.string ids used by the watchdog
{
  echo "package com.zaneschepke.wireguardautotunnel"
  echo "object R { object string {"
  grep -rhoE 'R\.string\.[a-z_0-9]+' "$app/xt" "$app/domain/enums/NotificationAction.kt" | sed 's/R.string.//' | sort -u | nl -w1 -s' ' | awk '{print "  const val "$2" = "$1}'
  echo "} }"
} > "$out/R.kt"
"$KOTLINC/bin/kotlinc" -jvm-target 17 -nowarn -cp "$ANDROID_JAR:$KOTLINC/lib/kotlinx-coroutines-core-jvm.jar" \
  "$here"/sim/*.kt "$out/R.kt" "$real"/*.kt "$app"/xt/*.kt -d "$out/sim.jar"
java -cp "$out/sim.jar:$ORG_JSON_JAR:$KOTLINC/lib/kotlin-stdlib.jar:$KOTLINC/lib/kotlinx-coroutines-core-jvm.jar:$ANDROID_JAR" SimTestKt
