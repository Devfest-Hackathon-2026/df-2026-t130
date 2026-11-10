#!/system/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
#
# Starts the rotep helper as the shell user. Run it from a PC with adb, in the top folder of the
# repository:
#
#   adb push apps/rotep/helper/rotep-helper.sh /data/local/tmp/rotep-helper.sh
#   adb shell sh /data/local/tmp/rotep-helper.sh
#
# The first line is needed once (and again when this script changes). The second starts the helper,
# which keeps running after the USB cable is unplugged.
#
# With the argument "fg" the helper runs in the foreground and prints its errors. Use it to find out
# why the helper does not start:
#
#   adb shell sh /data/local/tmp/rotep-helper.sh fg
#
# To see whether the helper is running:   adb shell pidof rotep-helper
#
# The helper finds the installed APK with `pm path`, so this script does not change when the app is
# installed again. See apps/rotep/diagnostics.md.

PKG=io.github.incallagent.rotep
MAIN=io.github.incallagent.rotep.shell.HelperMain
NAME=rotep-helper

APK=$(pm path "$PKG" | grep base.apk | head -n 1 | cut -d: -f2)
if [ -z "$APK" ]; then
  echo "rotep-helper: $PKG is not installed" >&2
  exit 1
fi

if [ "$1" = "fg" ]; then
  CLASSPATH="$APK" exec app_process /system/bin --nice-name="$NAME" "$MAIN"
fi

# setsid: a session of its own, so the helper does not belong to the adb session.
CLASSPATH="$APK" setsid app_process /system/bin --nice-name="$NAME" "$MAIN" </dev/null >/dev/null 2>&1 &
echo "rotep-helper: started (check with: pidof $NAME)"
