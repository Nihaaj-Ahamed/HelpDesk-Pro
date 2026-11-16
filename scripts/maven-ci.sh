#!/usr/bin/env bash
# Retry only Maven Central rate limits; compilation and test failures fail immediately.
set -uo pipefail
log_file=$(mktemp)
trap 'rm -f "$log_file"' EXIT
for attempt in 1 2 3; do
  mvn -B -U "$@" 2>&1 | tee "$log_file"
  result=${PIPESTATUS[0]}
  if [ "$result" -eq 0 ]; then exit 0; fi
  if [ "$attempt" -eq 3 ] || ! grep -Eiq '(status code: 429|Too Many Requests|Return code is: 429)' "$log_file"; then
    exit "$result"
  fi
  delay=$((attempt * 30))
  echo "Maven repository rate limited this build; retrying in ${delay}s (${attempt}/3)."
  sleep "$delay"
done
