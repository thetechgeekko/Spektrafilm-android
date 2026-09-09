#!/bin/bash
# Block until a connected device is thermally idle again, then report the
# temperature it was entered at.
#
# Why this exists: a fixed `sleep` is not a cool-down. A paired A/B of the
# grain-stage change was run with a 45 s sleep between captures and reported
# -12.7 % with a +/-1077 ms spread; the same two APKs behind this gate reported
# -20.8 % with +/-283 ms. The tell in the bad run was a phase whose code was
# identical in both arms moving by 74 %. See docs/research/perf-lab.md section 28.
#
#   usage: wait_cool.sh <serial> [skin_max_c] [ap_max_c] [cap_seconds]
#   prints: "cooled after <n>s (skin=.. ap=.. status=..)" and exits 0, or
#           "GATE CAP ..., proceeding HOT (...)" and exits 1 so the caller can
#           record that the sample was taken hot rather than silently trust it.
set -u
SERIAL="${1:?usage: wait_cool.sh <serial> [skin_max] [ap_max] [cap_s]}"
SKIN_MAX="${2:-35.5}"
AP_MAX="${3:-38.0}"
CAP="${4:-420}"

# Read the LIVE HAL block only. The "Cached temperatures" block above it can be
# minutes stale and will happily report a cool phone that is not cool.
hal_temps() {
  adb -s "$SERIAL" shell dumpsys thermalservice 2>/dev/null | awk '
    /Current temperatures from HAL:/ {inhal=1; next}
    /Current cooling devices/ {inhal=0}
    /^Thermal Status:/ {st=$3}
    inhal && /mName=SKIN/ {if (match($0,/mValue=[0-9.]+/)) skin=substr($0,RSTART+7,RLENGTH-7)}
    inhal && /mName=AP/   {if (match($0,/mValue=[0-9.]+/)) ap=substr($0,RSTART+7,RLENGTH-7)}
    END {printf "%s %s %s\n", (skin==""?"-1":skin), (ap==""?"-1":ap), (st==""?"-1":st)}'
}

t0=$SECONDS
while :; do
  read -r skin ap st <<< "$(hal_temps)"
  el=$((SECONDS - t0))
  if awk -v s="$skin" -v a="$ap" -v sm="$SKIN_MAX" -v am="$AP_MAX" \
         'BEGIN{exit !(s>0 && s<sm && a>0 && a<am)}'; then
    echo "cooled after ${el}s (skin=$skin ap=$ap status=$st)"
    exit 0
  fi
  if [ "$el" -ge "$CAP" ]; then
    echo "GATE CAP ${el}s reached, proceeding HOT (skin=$skin ap=$ap status=$st)"
    exit 1
  fi
  sleep 20
done
