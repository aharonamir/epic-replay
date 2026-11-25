#!/usr/bin/env bash
# T428: a connection log of the app on a connected phone. Every second it lists the sockets the app's user id has open (TCP and UDP, v4 and v6) from /proc/net, with the time,
# so the log can be set beside the run's stage times (events.jsonl). It writes the remote addresses and ports only, never any content.
#   t428_connlog.sh LOGFILE            runs until Ctrl-C
# Needs `adb` on the path and one phone connected. Loopback and the unspecified addresses are left out.
set -u
LOG=${1:?usage: t428_connlog.sh LOGFILE}
PKG=app.epicreplay
UID_N=$(adb shell dumpsys package $PKG | grep -m1 -oE '(userId|appId)=[0-9]+' | cut -d= -f2) # the first user's uid is the app id
[ -n "$UID_N" ] || { echo "the app is not installed" >&2; exit 1; }
adb shell "cat /proc/net/tcp6 >/dev/null 2>&1" || { echo "this phone does not let the shell read /proc/net" >&2; exit 1; }
echo "# t428 connection log of $PKG (uid $UID_N), started $(date -u +%FT%TZ)" > "$LOG"
hex_ip4() { local h=$1; printf '%d.%d.%d.%d' 0x${h:6:2} 0x${h:4:2} 0x${h:2:2} 0x${h:0:2}; }
while true; do
  now=$(date -u +%H:%M:%S)
  adb shell "for f in tcp tcp6 udp udp6; do echo \"== \$f\"; cat /proc/net/\$f; done" 2>/dev/null | tr -d '\r' | awk -v uid="$UID_N" -v now="$now" '
    /^== / { proto=$2; next }
    NR>0 && $8==uid && $3!~/^0+:0000$/ { print now, proto, "local", $2, "remote", $3, "state", $4 }' |
  while read -r t proto _ loc _ rem _ st; do
    ip=${rem%%:*}; port=$((16#${rem##*:}))
    case $proto in
      tcp|udp) addr=$(hex_ip4 "$ip") ;;
      *) case $ip in
           0000000000000000FFFF0000????????) addr=$(hex_ip4 "${ip:24:8}") ;; # a v4 address inside a v6 socket
           *) addr=$ip ;;                                                   # a real v6 address stays as the kernel prints it
         esac ;;
    esac
    case "$addr" in 0.0.0.0|127.*) continue ;; esac
    echo "$t $proto remote $addr:$port state $st"
  done >> "$LOG"
  sleep 1
done
