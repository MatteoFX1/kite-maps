# Changelog

Every version of Kite Maps, newest first. The small number goes up for fixes and polish, and
the middle number for big milestones.

## 0.7.2

- **One speed sign:** your speed and the speed limit are now one sign in the bottom left, like
  Waze: the limit on top, your speed under it, and the whole sign turns red when you're over.
- **Right lane at roundabouts:** coming off the M271 into Redbridge roundabout for the A33, Kite
  said "Middle lanes". It was reading the lanes for a slip road just before the roundabout. Now
  it only uses lanes that really lead into the roundabout, and otherwise follows the Highway Code
  (1st exit: left lane).
- **Faster rerouting after a wrong turn:** Kite now notices within a few seconds when you've left
  the route, or when you're driving back along it the wrong way. The new route sets off the way
  you're facing, instead of telling you to turn round on the road you're on.
- **Directions that match the blue line:** Kite takes its route from Google and its turn-by-turn
  from the open map. Before, the open map's directions were used even where it drove a slightly
  different way, so Kite could call out a turn the blue line never makes (like "Make a U-turn" leaving
  Park Gate, where the line goes round the roundabout). Now they're only used when both run on the
  very same roads, checked every 50 m both ways.
- **No more "Continue on…" chatter:** when only the road's name changes, Kite stays quiet, like Waze.
- **Google's lane advice:** where the open map has no lanes, Kite shows and says Google's own
  ("Use the right lane").
- **No pointless "Merge onto…":** when a slip road joins a road and its lane carries straight on
  to your next exit (like the A33 into Redbridge Road and off again), Kite no longer tells you to
  merge, and says which lane to stay in for the exit instead.
- **Smaller camera warning:** the speed camera card is about half the size, Waze-style, so it no
  longer hides the road. It's still bright orange (red when you're too fast) and pulses when it appears.
- **See more road ahead:** while driving, your arrow sits lower on the screen, so most of the map
  shows what's coming instead of what you've already passed.
- **Police heat map fixes:** its key ("Police ... often") is back above the risk chip, the glow
  now sits under your route, cameras and road names instead of covering them, and reports found
  in the background show on it straight away.
- **See what Waze sends:** Settings › Waze integration now has a "Last answer" line, like
  "3 police · 5 hazards · 140 road closures", so it's easy to tell if Waze stops sending a kind.

## 0.7.1

- **Waze-style driving map:** while you navigate, the map drops shop and bin icons, footpaths,
  buildings and one-way arrows, so only roads, water, parks and big road names remain.
- **Calmer night map:** slate land, soft blue-grey roads, green parks and blue water, closer to
  Waze. Marshes no longer show a bright grass pattern at night.
- **New car arrow:** a chunky 3D-style teal chevron replaces the old blue dot arrow.
- **Road name under the arrow:** the road you're on is shown just under the car.
- **Bolder route line** while driving.
- **Less on the driving screen:** the police risk chip only shows when risk is medium or high.
- **Arriving only counts on the route:** driving past close by on a parallel street no longer ends the trip.
- **Kite-style "End this trip?":** two big buttons in place of the grey iPhone pop-up.
- **Cleaner map screen:** the two big tiles under "Where to?" are gone. Home, Work and recent
  places are one tap away in "Where to?", and everything else is in Saved.
- **Releases show what's new:** each GitHub release lists its changes from this file.
- **Fixes:**
  - Ending a trip while Kite was finding a new route could make it talk again afterwards.
  - Adding a stop while rerouting could lose the stop.
  - Picking a suggestion while a search was still loading could swap the place for the results.
  - The "End this trip?" card steps aside when you arrive.
  - The road name no longer hides under the police helicopter or police risk chip.
  - The trip sheet pulled up to full height no longer slides under the from/to box.
  - Search filters that hide every result say so, instead of showing an empty list.
  - Other routes that take the same time say "Same time", not "+1 min".
  - UK postcodes typed in "Where to?" show up as you type, even without Google.
  - Opening Kite with no signal no longer leaves Google search half set up once signal is back.

## 0.7.0

The driving-first redesign: a new navy and teal look, a bottom dock with Map, Saved and
Settings, a big turn banner with real roundabout drawings and lanes, a calmer nav screen,
smooth arrow and camera movement, clearer motorway wording, and easier home and work setup.

## 0.6.1

Waze reports work again after Waze added a reCAPTCHA check.

## 0.6.0

Live Waze reports (police, crashes, hazards), a police heat map and risk score, and police
aircraft alerts.

## 0.5.3

The results list scrolls instead of pulling the sheet down on newer iOS.

## 0.5.2

The results list scrolls at full height.

## 0.5.1

The place sheet no longer shakes while dragged.

## 0.5.0

Renamed to Kite Maps, with a new kite logo, a new README and an AltStore / SideStore source.

## 0.4.1

Dropped pins stay open, bookmarks can be renamed, and pasted Google, Waze and Apple map links
open in Kite.

## Earlier

Before 0.4.1, Kite (then "Vela for iPhone") grew in a few big steps without numbered releases:
the map, search and place details came first. Then came directions, turn-by-turn and speed
cameras like Vela, then Waze-style camera and lane alerts, UK tuning and transit, and then press
and hold to copy and Street View.
