#!/system/bin/sh
# SPDX-License-Identifier: GPL-2.0-or-later
# Collect locally: Wi-Fi latency must not overrun SurfaceFlinger's short history.
set -eu
out=$1
layer=$2
duration=$3
case "$duration" in ''|*[!0-9]*) exit 2 ;; esac
[ "$duration" -ge 1 ] && [ "$duration" -le 300 ]
read up rest < /proc/uptime
stop=$(( ${up%%.*} + duration ))
exec > "$out"
while :; do
    read up rest < /proc/uptime
    printf 'SAMPLE %s\n' "$up"
    dumpsys SurfaceFlinger --latency "$layer"
    printf 'END\n'
    [ "${up%%.*}" -ge "$stop" ] && break
    sleep 0.5
done
