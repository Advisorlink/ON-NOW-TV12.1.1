#!/usr/bin/env bash
set -u
LOG=/tmp/one_full.log
exec >"$LOG" 2>&1
echo "== start $(date)"
echo "WEB prebuilt"


VC=6 VN=1.2.0 bash /root/one_build.sh assembleRelease
tail -4 /tmp/one_build.log
echo "== done $(date)"
