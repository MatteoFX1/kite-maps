# Vela for iPhone

An iPhone version of [Vela Maps](https://github.com/PimpinPumpkin/Vela), the private, open-source
maps app for Android. It's meant for sideloading, not the App Store.

## What works

- **The map** in Vela's colors: navy at night, classic by day, on OpenFreeMap tiles. Live
  traffic and satellite from the layers button.
- **Search** with Google Maps results, no account and no key, the way Vela does it: suggestions
  while you type, category chips, pins with names and ratings, open now, sort and rating filters. If Google doesn't
  answer, results come from OpenStreetMap.
- **Places**: photos, rating, open or closed, hours, about, phone, website, save and share. Tap
  anything on the map to look it up, or long-press to drop a pin.
- **Directions** inside the app: drive, bike and walk, live traffic times, alternate routes and
  the steps. Avoid tolls or highways.
- **Turn-by-turn navigation** with a voice, rerouting, a following camera, the arrival time and
  the speed limit, and it keeps going with the screen off. Like Vela, the turns come from the open
  OSRM router run along Google's route.
- **Public transit directions** and **live departure boards** at stops and stations, from
  Transitous (open timetables, no account), with line colors and live delays. Subway, tram and
  rail lines can be drawn on the map.
- **Waze-style driving help**: lane arrows, a roundabout picture with the exit to take, road
  signs in the banner, and camera warnings with a chime, the camera's limit and a countdown bar.
  UK average speed checks show your average against the limit.
- **Speed cameras and license plate cameras** (DeFlock) from OpenStreetMap on the map. How many
  are found depends on what volunteers have mapped in your area.
- **Tuned for the UK**: miles and yards, British limit signs and road plates, postcode search,
  and clockwise roundabouts where traffic drives on the left.
- **Your places**: home, work, parked car, recent places, stops on the way, voice search and
  Siri shortcuts.
- **Settings**: theme, units, voice, and "Use Vela without Google" (OpenStreetMap search and the
  FOSSGIS router only).

## Get the app

1. Open the **Releases** section of this repo (right side of the main page) and download
   `Vela.ipa` from the newest one.
2. Install it with [AltStore](https://altstore.io) or [Sideloadly](https://sideloadly.io) using
   your Apple ID. With a free Apple ID the app has to be refreshed every 7 days.

Every change merged into `main` builds a new release automatically (GitHub Actions, on a Mac
runner). Pull requests build too: their `.ipa` is under the run's **Artifacts** in the Actions tab.

## How it's built

- `App/` is the SwiftUI app with the [MapLibre](https://maplibre.org) map.
- `VelaCore/` is the search and routing code (Google extractor, OpenStreetMap and FOSSGIS
  fallbacks, route tracking), ported from Vela's Kotlin `:core` module. `swift run vela-probe pizza` runs a live search from a Mac.
- `project.yml` describes the Xcode project; [XcodeGen](https://github.com/yonaskolb/XcodeGen)
  turns it into `Vela.xcodeproj`.

## License

GPL-3.0, like Vela. See [LICENSE](LICENSE).
