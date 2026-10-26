#!/system/bin/sh
# Probe a Lincoln/Ford head unit's instrument cluster for the channel it accepts.
#
# Run this ON THE HEAD UNIT (adb shell), while CarPlay is connected with an active route in
# Apple Maps. It listens to every broadcast on the cluster channel, can inject one maneuver on the
# Baidu navigation protocol the working build uses, and prints what the cluster reacted to.
#
# The cluster app usually logs its own parsing; this script watches that log alongside the
# broadcasts so you can tell which channel it reads. Watch the cluster screen as well.
#
# Usage:
#   adb shell sh /data/local/tmp/lincoln-cluster-probe.sh            # passive listen (30 s)
#   adb shell sh /data/local/tmp/lincoln-cluster-probe.sh inject     # also inject a maneuver
#   adb shell sh /data/local/tmp/lincoln-cluster-probe.sh progress   # drive the junction progress bar
#
# Nothing here changes persistent state; it only sends broadcasts a connected CarPlay session
# would send anyway, and leaves guidance closed at the end.

CLUSTER="${CLUSTER:-com.baidu.xiaoduos.instrumentcluster}"
MODE="${1:-listen}"

echo "== Lincoln cluster probe =="
echo "cluster package: $CLUSTER"
echo

echo "-- is the cluster installed? --"
pm path "$CLUSTER" 2>&1
echo

echo "-- its signature (SHA-256) --"
APK=$(pm path "$CLUSTER" 2>/dev/null | head -1 | sed 's/^package://')
if [ -n "$APK" ]; then
  # Toybox on Android has sha256sum on newer builds; fall back to openssl if present.
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$APK"
  elif command -v openssl >/dev/null 2>&1; then
    openssl dgst -sha256 "$APK"
  else
    echo "(no sha256sum/openssl; skip)"
  fi
fi
echo

echo "-- other likely cluster apps present --"
for p in com.baidu.xiaoduos.instrumentcluster com.baidu.car.navi org.savior.sync \
         com.byd.cluster com.byd.automap com.byd.launchermap; do
  if pm path "$p" >/dev/null 2>&1; then echo "  present: $p"; fi
done
echo

echo "-- recent cluster log (last 200 lines, filtered) --"
logcat -d -t 200 2>/dev/null | grep -iE "instrument|cluster|navi|xiaoduos|remain|eta" | tail -40
echo

if [ "$MODE" = "listen" ]; then
  echo "-- passive listen: watching broadcasts for 30 s --"
  echo "   (set an Apple Maps destination now; press Ctrl-C to stop)"
  timeout 30 logcat -v brief 2>/dev/null | grep -iE "navi|instrument|cluster|PLAY_INFO|remain" | head -60
  echo
  echo "Done. If you saw navi lines, the cluster reads guidance over broadcasts; re-run with"
  echo "  sh $0 inject"
  echo "to send one maneuver on the protocol the working build uses."
  exit 0
fi

if [ "$MODE" = "progress" ]; then
  # --- the junction identity the cluster's own navigation adapter divides -----------------------
  #
  # The adapter records a junction's starting distance when the latitude/longitude pair it is handed
  # changes, and then draws `distance / start * 256` itself. It has no clamp, so a receiver that
  # missed the first packet would compute more than 256 from a later, smaller distance; DiPlay
  # therefore sends a non-increasing distance under one identity (LincolnManeuverTracker). This mode
  # drives that sequence directly: one identity, a distance that only falls, and the log below prints
  # the BargraphSteps the adapter hands the vehicle service.
  #
  # Turn the Lincoln navigation switch off in DiPlay first, or DiPlay's own packets fight these.
  IDENTITY_LAT=1.000000000000001
  IDENTITY_LON=1.000000000000002
  ROAD=Probe
  FLAGS=32

  echo "-- progress: one identity, falling distance (350 -> 250 -> 150 -> 50 m) --"
  am broadcast -a com.baidu.car.navi.STATE -f $FLAGS --es state TRUE -p "$CLUSTER" >/dev/null 2>&1
  for d in 350 250 150 50; do
    am broadcast -a com.baidu.car.navi.INFO -f $FLAGS -p "$CLUSTER" \
      --es name turn_left --ei distance $d --es nextRoadName "$ROAD" --ei laneCount 0 \
      --ed latitude $IDENTITY_LAT --ed longitude $IDENTITY_LON >/dev/null 2>&1
    echo "  sent distance=$d under one identity"
    sleep 2
  done

  echo
  echo "-- cluster log during the progress run --"
  logcat -d -t 200 2>/dev/null | grep -iE "NavPresenter|BargraphSteps|sendNaviInfo|ManeuverElement|StreetName" | tail -30
  echo
  echo "BargraphSteps should fall 256 -> 182 -> 109 -> 36 for 350/250/150/50 m against a 350 m start."
  echo "If it stays at 1, the adapter never established a baseline: check that both --ed values reached"
  echo "it as doubles, because an adapter that takes its default (0, 0) never starts a junction."
  echo
  echo "-- clearing guidance --"
  am broadcast -a com.baidu.car.navi.STATE -f $FLAGS --es state FALSE -p "$CLUSTER" >/dev/null 2>&1
  echo "Done."
  exit 0
fi

echo "-- inject: sending candidate extras one name at a time --"
echo "   watch the cluster screen and the log below; note which name changes the display."
echo

# This is the channel the build that works on the MEK-MX8Q / CX483 car uses
# (`DiPlay-0.2.12-增加仪表.apk`): the head unit's own Baidu navigation protocol, the same one the
# stock com.baidu.naviauto app and the launcher speak. The turn index is the navigation app's own
# arrow (0 turn_back, 1 turn_front, 2 turn_right_front, 3 turn_right, 7 turn_left, 8 turn_left_front,
# 9 turn_ring, 10 turn_ring_out, 24 turn_dest). 32 is FLAG_INCLUDE_STOPPED_PACKAGES.
TURN_INDEX=7
DIST=350
ROAD=Probe
FLAGS=32

echo "  announcing navigation (open + KEY_TYPE 10101/NAVI + start_navigation)"
am broadcast -a ACTION_PHONE_NAV_ACTIVE_OPEN -f $FLAGS -p "$CLUSTER" >/dev/null 2>&1
am broadcast -a com.baidu.naviauto.STANDARD_BROADCAST_SEND -f $FLAGS --es KEY_TYPE 10101 --es MODULE_TYPE NAVI -p "$CLUSTER" >/dev/null 2>&1
am broadcast -a com.baidu.naviauto.start_navigation -f $FLAGS >/dev/null 2>&1
sleep 1

echo "  turn-by-turn ($TURN_INDEX, $DIST m, $ROAD)"
am broadcast -a com.baidu.naviauto.action.send -f $FLAGS -p "$CLUSTER" \
  --ei KEY_TURNTYPE $TURN_INDEX --ei turnType $TURN_INDEX --ei maneuverId $TURN_INDEX --ei resid $TURN_INDEX \
  --es maneuverName turn_left.png --es icon_name turn_left.png \
  --es distance "$DIST" --ei remainDist $DIST --ei remainDistance $DIST --ei remain_dist $DIST \
  --es maneuverTips "$DIST 米" \
  --es StreetName "$ROAD" --es StreetName2 "" --es CurentStreetName "$ROAD" --es road_name "$ROAD" \
  >/dev/null 2>&1
sleep 2

echo
echo "-- cluster log during injection --"
logcat -d -t 160 2>/dev/null | grep -iE "NavPresenter|instrument|remain|ManeuverElement|StreetName" | tail -40
echo
echo "If the cluster draws nothing while the turn-by-turn above is accepted, the cluster is not the"
echo "receiver on this firmware: watch for the navigation view opening instead, and re-run the"
echo "passive listen to see which broadcast the cluster actually reacts to."
echo
echo "-- clearing guidance --"
am broadcast -a ACTION_PHONE_NAV_ACTIVE_CLOSE -f $FLAGS -p "$CLUSTER" >/dev/null 2>&1
am broadcast -a com.baidu.naviauto.STANDARD_BROADCAST_SEND -f $FLAGS --es KEY_TYPE 10100 --es MODULE_TYPE NAVI -p "$CLUSTER" >/dev/null 2>&1
am broadcast -a com.baidu.naviauto.stop_navigation -f $FLAGS >/dev/null 2>&1
echo "Done."
