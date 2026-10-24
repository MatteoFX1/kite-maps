"""Writes apps.json, the AltStore / SideStore source for Kite Maps.

People add the source's link in AltStore or SideStore once, and the app updates itself
whenever a new build lands. The build workflow runs this after each release on main.

Usage: make_source.py <Kite.app> <Kite.ipa> <entitlements.plist> <release tag> <owner/repo> <out.json>
"""
import datetime
import json
import os
import plistlib
import sys

app, ipa, ents_path, tag, repo, out = sys.argv[1:7]

with open(os.path.join(app, "Info.plist"), "rb") as f:
    info = plistlib.load(f)

entitlements = []
if os.path.getsize(ents_path) > 0:
    with open(ents_path, "rb") as f:
        entitlements = sorted(plistlib.load(f).keys())

raw = f"https://raw.githubusercontent.com/{repo}/main"
version = {
    "version": info["CFBundleShortVersionString"],
    "buildVersion": info["CFBundleVersion"],
    "date": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    "localizedDescription": f"Build {info['CFBundleVersion']}. See what's new on GitHub: https://github.com/{repo}/releases/tag/{tag}",
    "downloadURL": f"https://github.com/{repo}/releases/download/{tag}/Kite.ipa",
    "size": os.path.getsize(ipa),
    "minOSVersion": info.get("MinimumOSVersion", "16.0"),
}

source = {
    "name": "Kite Maps",
    "identifier": "app.vela.ios.source",
    "subtitle": "A free, open-source maps app for iPhone.",
    "description": "The official source for Kite Maps, an unofficial iPhone port of Vela Maps.",
    "iconURL": f"{raw}/docs/icon.png",
    "website": f"https://github.com/{repo}",
    "tintColor": "#14857A",
    "apps": [
        {
            "name": "Kite Maps",
            "bundleIdentifier": info["CFBundleIdentifier"],
            "developerName": "MatteoFX",
            "subtitle": "Maps, search and turn-by-turn navigation.",
            "localizedDescription": (
                "Kite Maps is a free, open-source maps app: search places, get directions, "
                "voice navigation with speed cameras and lane guidance, transit, Street View and more. "
                "It's an unofficial iPhone port of Vela Maps by PimpinPumpkin."
            ),
            "iconURL": f"{raw}/docs/icon.png",
            "tintColor": "#14857A",
            "category": "navigation",
            "screenshots": [f"{raw}/docs/screenshots/{n}.jpg" for n in ("browse", "place", "directions", "nav", "camalert", "transit")],
            "versions": [version],
            "appPermissions": {
                "entitlements": entitlements,
                "privacy": {k: v for k, v in info.items() if k.startswith("NS") and k.endswith("UsageDescription")},
            },
        }
    ],
    "news": [],
}

with open(out, "w") as f:
    json.dump(source, f, indent=2)
    f.write("\n")
print(json.dumps(version, indent=2))
