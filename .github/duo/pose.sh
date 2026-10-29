#!/bin/bash
# Changes the simulated iPhone Duo's pose. simctl can't, so this clicks the pose buttons under the
# device in Xcode's DeviceHub app. Usage: pose.sh open|rotate
# The buttons' places are fractions of the screen, read off a screenshot of DeviceHub on the build Mac.
pgrep -x DeviceHub > /dev/null || { open /Applications/Xcode.app/Contents/Applications/DeviceHub.app; sleep 30; }
command -v cliclick > /dev/null || brew install cliclick > /dev/null 2>&1
osascript -e 'tell application "DeviceHub" to activate' > /dev/null 2>&1 || true
sleep 1
case $1 in
  open) FX=0.6921 ;;
  rotate) FX=0.6229 ;;
esac
B=$(osascript -e 'tell application "Finder" to get bounds of window of desktop')
W=$(echo "$B" | awk -F', ' '{print $3}'); H=$(echo "$B" | awk -F', ' '{print $4}')
cliclick c:$(python3 -c "print(int($FX*$W))"),$(python3 -c "print(int(0.8838*$H))")
sleep 8
