# Vela for iPhone

An iPhone version of [Vela Maps](https://github.com/PimpinPumpkin/Vela), the private, open-source
maps app for Android. It's meant for sideloading, not the App Store.

## What works (stage 1)

- The map, with OpenFreeMap tiles (the same basemap Vela uses), in light and dark.
- Search: Google Maps results with no account and no key, the way Vela does it. If Google
  doesn't answer, results come from OpenStreetMap instead.
- Place details: photos, rating, open or closed, hours, phone, website, address, share, and a
  Directions button (Apple Maps for now).

Next: Vela's own routing and turn-by-turn navigation, then extras.

## Get the app

1. Open the **Releases** section of this repo (right side of the main page) and download
   `Vela.ipa` from the newest one.
2. Install it with [AltStore](https://altstore.io) or [Sideloadly](https://sideloadly.io) using
   your Apple ID. With a free Apple ID the app has to be refreshed every 7 days.

Every change merged into `main` builds a new release automatically (GitHub Actions, on a Mac
runner). Pull requests build too: their `.ipa` is under the run's **Artifacts** in the Actions tab.

## How it's built

- `App/` is the SwiftUI app with the [MapLibre](https://maplibre.org) map.
- `VelaCore/` is the search code (Google extractor and OpenStreetMap fallback), ported from
  Vela's Kotlin `:core` module. `swift run vela-probe pizza` runs a live search from a Mac.
- `project.yml` describes the Xcode project; [XcodeGen](https://github.com/yonaskolb/XcodeGen)
  turns it into `Vela.xcodeproj`.

## License

GPL-3.0, like Vela. See [LICENSE](LICENSE).
