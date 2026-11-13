# Changelog

Every version of Kite Maps, newest first. The small number goes up for fixes and polish, and
the middle number for big milestones.

## 0.7.11 (iPhone and Android)

Another check of the basics on both phones, with the bugs it found fixed, and turn-by-turn checked
on 40 of Britain's trickiest junctions (Spaghetti Junction, both Magic Roundabouts, Hanger Lane,
Catthorpe, Almondsbury, the M27/M3 and Eastleigh roundabouts and more).

- **No more missing turns:** Kite said nothing where the A38(M) splits at Spaghetti Junction
  ("Keep right to stay on A38(M)"), skipped the second of two roundabouts close together (Six
  Ways, Erdington), and left out some merges ("Merge onto M8"). Turns the map's directions leave
  out are now filled in from Google's.
- **Lanes and signs on more routes:** on about half the test routes, one stretch where the map's
  directions went a slightly different way made Kite drop them for the whole trip, losing the
  painted lanes and road signs. Now only that stretch uses Google's directions.
- **Junction numbers:** motorway exits say the number as well as where they go, like "Take exit 4
  toward The Midlands and London", and a bare "Take the exit" gets the sign's destination
  ("towards Emsworth").
- **Two close turns said together:** when the next turn comes within about 70 m, Kite says both,
  Waze style: "Turn left, then turn right onto King Street".
- **Roundabouts Google knows about:** where the map only says "Turn right" but there's a
  roundabout (Otterbourne Hill), Kite now says "At the roundabout, take the 2nd exit".
- **Cleaner steps:** one turn is no longer told twice ("Turn right", then "Continue straight onto
  Wide Lane" 11 m later is now "Turn right onto Wide Lane"), a turn that keeps you on the same
  road says "to stay on", keep-left and keep-right steps show a keep arrow instead of a full turn,
  and a turn where the road bends away is no longer called "Continue straight".
- **Fewer wrong turns at the start:** setting off near a junction, Kite could say "Make a U-turn,
  then turn sharp right" where you just bear left (Romsey). That false U-turn is gone.
- **The way the road really goes:** where the map said "Keep right at the fork" but the road turns
  left (Castle Lane West, Bournemouth), Kite now says "Turn left".
- **Roundabout pictures match the words:** the picture could draw the 3rd exit while Kite said
  "take the 2nd exit". The picture now always counts the same exit as the voice.
- **Big arrival time:** the bar at the bottom shows three big numbers side by side, like Waze:
  when you'll get there, minutes left and miles left. The arrival time turns orange or red when
  traffic is making you late.
- **One warning at a time:** only the nearest warning gets the big card; any others wait in a small
  "Also ahead" pill under it (tap it for a quick look). A camera you're speeding towards always
  comes first. The camera card no longer shows the same limit as the sign next to it.
- **Voice: On, Alerts only, Off:** the speaker button now has a middle setting that says cameras,
  police and hazards but not the turns, and shows which one you picked for a moment.
- **Tidier map:** zoomed out over a busy town, cameras and minor reports close together join into
  one bubble with a number (tap it to zoom in). Police and crashes always keep their own icon.
- **Long drives load again:** on a long trip like Southampton to Newcastle, when the map's own
  directions server was slow (often at night), Kite could sit on "Finding routes…" for minutes.
  The step that lines its turns up with Google's route now takes a fraction of a second.
- **Roundabouts say the road:** the voice now says "take the 3rd exit onto West End Road", like the
  banner, instead of sometimes leaving the road off.
- **New police heat map look ("blue lights"):** roads where police get reported now glow blue,
  turning violet and pink at the worst spots, instead of a muddy yellow-to-red haze that looked
  like traffic jams. Quiet roads stay clear, and closer in it follows the roads sharply instead
  of going blurry.
- **Speed limits on long drives:** if the speed limit service stopped answering partway through
  a long route, the sign could stay blank after the first 25 km for the rest of the drive. Kite
  now fills in the rest from its other map source, like it does when that service is down.
- **After arriving:** a new route or "Found a faster route" that was still loading when you
  arrived no longer starts the trip up again in the car park.
- **Speed limit sign:** a car park or "walking pace" road no longer shows a "0" mph sign (or says
  "limit 0 miles per hour"); it shows 5, the lowest sign there is.

## 0.7.10 (iPhone and Android)

Live cameras, finished off after checking them against the request (GitHub #15).

- **Webcams on your route:** "Cameras on your route" now shows Windy's webcams in the towns you
  drive through, not just UK road cameras, so it works anywhere in the world.
- **Webcams when zoomed out:** the violet webcam dots now show from a whole-region view, like
  Windy. Road cameras still show from a city view, so the map stays clean.
- **Layers button:** turning Live cameras on now brings back both road cameras and webcams, even
  if one of them was switched off in Settings.

## 0.7.9 (iPhone and Android)

A check of the basics on both phones, with the bugs it found fixed. Nothing looks different.

- **Average speed checks:** Kite no longer says "Average speed check ended" and then starts the
  same check again a moment later when the road data updates. After a reroute or a faster route
  in the middle of a check, it carries on with your average so far instead of starting again.
- **"GPS signal lost" (Android):** no longer shows while GPS is working when the phone's clock
  is a few seconds out.
- **Staying on the route (Android):** Kite no longer loses its place on the route for a moment
  once the roundabout pictures have loaded, like the iPhone.
- **After arriving (Android):** the screen can go to sleep straight away, like the iPhone,
  instead of staying on for 3 more minutes.

## 0.7.8 (iPhone and Android)

Everything ChatGPT found when it went through both apps as a second reviewer, fixed and checked,
plus live cameras.

- **Live cameras (GitHub #15):** see the latest photo from traffic cameras and webcams, like the
  weather app Windy. Tap a blue camera dot on the map for a big photo with how old it is,
  refresh it, open it full screen or watch the short video or timelapse. All of the UK's public
  road cameras are there: National Highways (England's motorways and A roads), Transport for
  London, Traffic Scotland, Traffic Wales and TrafficWatch NI, plus Windy's scenic webcams
  worldwide (violet dots).
- **Cameras on your route:** before you press Go, a row of photos from the cameras you'll pass,
  in order, so you can see jams, rain or snow ahead. Tap one to look closer and step through them.
- **Easy to switch off:** Settings › Live cameras turns it all off, or just road cameras,
  webcams or the route row. There's a "Live cameras" button in Layers too. Never shown while
  driving.
- **Broken cameras hidden:** when a road camera is off, Kite says "No photo right now" instead of
  showing the "camera unavailable" picture.
- **Driving:** a trip no longer ends early when the route passes your destination, or before its
  stops are done. Lane advice is only shown for its own junction. The first turn is said with its
  distance ("In 300 yards, turn left"). "GPS signal lost" shows in tunnels instead of a frozen
  screen. Adding a stop that can't be reached is said, not silently ignored.
- **Search and places:** tapping a place no longer swaps it for the business next door. Saved
  places show today's opening times. Searching and routes right by the Greenwich line work again
  (they failed within about 70 m of it). With no location yet, the iPhone starts in London.
- **Road alerts:** a number plate camera no longer silences the speed camera right after it, and
  a crash or police report is no longer hidden by a smaller report next to it. Police aircraft
  stop showing once they're out of date.
- **Privacy:** Kite no longer asks Waze about the areas around your saved Home and Work. "Use Kite
  without Google" also stops Google photos and Waze's hidden page, and no Google traffic loads
  before you've made your choice on the welcome screen.
- **Links:** Google directions links open the destination, Apple Maps links open the right place,
  and "52, -1.5" works as coordinates. Android opens www.google.com/maps links.
- **Android:** fixed a crash when a drive ended straight after it started. Kite says when the
  phone's Location switch is off. GPS turns off when Kite is closed and you're not driving. Shortcuts
  no longer end the drive you're on. Wi-Fi to mobile data no longer looks like "No internet".
  The microphone permission is gone.

## Android 0.7.7

- **Welcome screen fixed on Pixel phones:** the "Get started" button was pushed off the bottom
  of the screen, so you couldn't get into the app. The full-screen pages (trip steps, pickers,
  photos) are fixed the same way.
- **F-Droid repo fixed:** it was stuck on "checking repo" because the repo's index was never
  made. Adding the repo now works, and new versions show up as updates.

## Android 0.7.6

- **Kite for Android, first release:** the iPhone app, screen for screen, written fresh for
  Android: the big driving screen with lanes and real roundabout pictures, speed cameras with
  their direction, your speed and the limit, live Waze police and road reports, the Highway Radar
  police heat map, Street View, transit, saved places and Home, Work and Car shortcuts.
- **No Google Play Services needed:** works on GrapheneOS. Updates come from Kite's own F-Droid
  repo (see the README).

## 0.7.6

- **Quieter about cameras on the other side of the road:** when the map says a speed camera
  checks the other way *and* it stands on the far side of the road, Kite still shows it on screen
  but doesn't chime or speak. It isn't hidden, because the map's direction is wrong for about one
  camera in six. Cameras with no direction on the map warn as before. Number plate cameras are
  unchanged.
- **Police and drivers' reports on the map all the time:** not just along a route while you
  navigate. Open the map at city zoom and Kite asks Waze about the area on screen, at most every
  2 minutes or when you move the map well away (navigating, it still asks along the route).
- **The police heat map works straight away, for the whole UK:** it now comes from Highway
  Radar's own heat map (built from its drivers' reports over time) instead of only the reports
  this phone had seen, which took a week of driving to fill. It's their private server, so it may
  stop one day. Thanks to Ausbloke's fork for finding it.
- **Waze is asked less often while you drive:** the map was asking several times a second; now
  at most every 90 seconds while navigating, and every 5 seconds while browsing.
- **The police heat map is on from the start**, on the map and while you drive (you can still
  turn it off in Settings).
- **The heat map is softer and its key matches:** close in it no longer tints whole streets
  orange in big squares, and fades further as you zoom into a busy city centre, and the little key now shows the same yellow-to-red as the map (it
  showed blue to red).
- **No "Rerouting" just before "You have arrived":** a last-second reroute right by the
  destination stays quiet.
- **Road names read out in full:** "Waterloo Brg" is said "Waterloo Bridge", and "Cowbridge Road E"
  is "Cowbridge Road East", "W Approach Road" is "West Approach Road", "Copperas Hl" is "Copperas Hill".
- **"In 250 yards, your destination is ahead"** instead of "In 250 yards, you have arrived".
- **Search puts the nearby place first:** "Covent Garden Market" came up in Canada before London,
  in the results and in the suggestions while you type.
- **The turn banner writes compass letters out:** "W Approach Rd" reads "West Approach Rd".
- **Search suggestions stay put while you type:** a row no longer moves under your finger as
  more suggestions arrive, so you open the one you tapped.
- **Places off the road end the trip properly:** a pin in a park, on a pier or under a motorway
  counts as reached when you get near it or park within 150 m, instead of sending you round the
  block again and again.
- **Quieter on phone calls:** drivers' reports, police hotspots and police aircraft show on
  screen but aren't spoken over your call. Speed cameras are still said.
- **No old turns while a new route loads:** after a wrong turn, Kite could still read out a turn
  from the route you'd just left.
- **One police warning, not two:** the same police car listed twice by Waze was said twice.
- **The drive time on a place card matches the route:** it said "23 min drive" for a 29 min
  drive, as it came from the open router without traffic. It now asks the same way the
  directions do, with traffic and your avoid settings.
- **The route line's teal-to-cyan fade works again on the newest iOS**, which refused the old way
  of writing it.
- **Sending a place to Apple Maps keeps its whole name:** "M&S" went over as "M".
- **Rerouting knows which way you're going even when the phone doesn't say:** with no compass
  course from the GPS, Kite works it out from the last few seconds of driving, so a new route
  doesn't start back the way you came.
- **Arrived card:** "You've arrived", the place name and Done are never cut short or wrapped
  ("Don / e"); on a narrow screen the buttons move under the words.
- **Cameras on the map load even when the map server is busy:** Kite tries again after 30 seconds
  instead of waiting for you to move the map.
- **A place's distance is from where you are now,** not from where you searched (it still said
  "11 mi" after arriving).
- **Surveillance cameras layer (off by default):** CCTV cameras from OpenStreetMap, switched on
  in Layers or Settings. Tap one to see who runs it. Map only, no warnings.
- **Saved places stay saved:** tapping a place on the map you'd already saved from search now shows
  it as saved, and the pin stays where you tapped instead of jumping to a nearby result.
- **Saved-place names are easier to read** on the dark map.
- **GPS warnings:** a card tells you when Precise Location is off for Kite, or when GPS is poor
  (worse than 200 m). The "police risk here" card waits for a good fix and is now called "Local
  report activity".
- **Share diagnostics** (Settings, About): a small file with your Kite version, iPhone model,
  settings and whether Waze and OpenStreetMap answer, to send with a bug report.
- Thanks to Ausbloke's fork (github.com/NotDiscordOfficial/kite-maps) for these five.

## 0.7.5

- **Slip roads show a slip road arrow, not a merge:** Google names some slip roads without saying
  which side they leave on ("Take the M3 slip road" on Leigh Road, "Exit at junction 10"), and
  Kite showed those as a merge. They now get the slip road arrow, on the side the road really
  goes (8 of 40 slip roads were wrong on 14 drives; none wrong after the fix, in two rounds of
  24 drives across England, Wales and Scotland).
- **More than one good lane at big roundabouts:** going right on a road with three lanes or more,
  the middle lane is lit as well as the right one (M27 junction 3 from the M271). Where the map
  has the painted lane arrows, or Google says which lanes to use, Kite follows those instead.

## 0.7.4

- **Police, crashes and hazards from Waze drivers, free, with no key:** while you navigate, Kite
  asks Waze's own app servers for drivers' reports along the next 25 miles every 90 seconds, the
  way the Waze app does, with an anonymous account (no name or email). They pop up, show on the
  map and fill the police heat map. Settings › Road alerts shows whether it's working. The way of
  asking comes from SABRE Plus for Highway Radar (MIT licence).
- **No more OpenWeb Ninja key:** the key box and checks counter are gone, and the saved key is wiped
  from the phone.
- **Gentler when Waze is down:** Kite waits longer after each failed ask (up to 10 minutes), and
  drops drivers' reports after 5 minutes without an answer, so it never warns about old ones.
- **Road alerts says plainly where reports come from**, with separate lines for closures and
  drivers' reports.
- **Tidy-up:** a few bits of unused code removed.
- **Roundabout picture points the right way:** a "1st exit" that goes straight on was drawn
  turning left (Chestnut Avenue, Eastleigh). The arrow now follows the way the route really
  leaves every roundabout, big ones included (checked on 64 roundabouts in Hampshire, Wales,
  London and Milton Keynes).
- **Big motorway roundabouts point the right way too:** at M27 J3 (Rownhams) and J5 (Stoneham)
  the picture pointed along the roundabout itself, not the road you leave on. It now looks at
  where the route actually leaves the circle, so the arrow matches the exit (also Bury Road).
- **Lanes match the road:** at roundabouts the lane boxes now show as many lanes as the road in
  really has (3 at J3), using the Highway Code rule for which lane to be in. On a one-lane road
  in (Bury Road) there's no lane advice, since there's nothing to choose.
- **Mini-roundabouts in the banner:** the small painted ones (Normandy Way, Marchwood) were
  skipped because neither Google nor the open map router mentions them. Kite now finds them on
  the map itself and shows which exit to take, with a picture, like the big ones.
- **Speed limit on more roads:** where the map has a limit at the start of a road but not further
  along (Normandy Way), Kite keeps showing it until you turn onto a different road, the way the
  last sign you passed still applies.
- **Fewer road numbers while driving:** M27, M3 and A27 signs are spaced much further apart, so a
  motorway junction isn't covered in them, and none sit under your arrow.
- **No more "19" speed signs:** some British roads are mapped as "30" with no unit, which the map
  treats as km/h (19 mph). In Britain Kite now reads those as the 30 mph on the sign.
- **iPhone Duo support:** Kite now fills both of the foldable's screens instead of running small
  with black bars (it's built with the newest Xcode). Opened up sideways, the search, place and
  trip cards move into a panel on the left with a big map beside it, clear of the fold, and the
  car drives in the middle of the map. Ordinary iPhones look the same, apart from pop-up sheets
  like Layers taking the newer iOS rounded style.

## 0.7.3

- **Police and crash alerts are back (with your own free key):** Settings › Road alerts › Police
  and crashes. Paste a free OpenWeb Ninja key and Kite warns you about police, mobile speed
  cameras and crashes reported by Waze drivers, shows them on the map and fills the police heat
  map. The free plan has 50 checks a month, so Kite only checks while you navigate, once every 10
  minutes, and shows how many you've used.
- **"Waze integration" is now "Road alerts":** it only shows what really works. Without a key
  that's road closures and police aircraft, since Waze's live map no longer shares anything else.
- **New map look, Waze-style in Kite's teal:** a thicker teal route with chevrons, a chunky white
  turn arrow, your arrow on a disc (white by day, navy at night), and the road you've driven
  fades out. Your location dot and buttons are teal too.
- **Arrow lower on screen:** your arrow sits about three quarters of the way down, so you see more
  road ahead. The zoom is the same as before. Sideways, it no longer sits on the trip bar.
- **Traffic lights on the map** along your route while you drive, like Waze.
- **Clearer roundabouts:** the map arrow goes all the way round to your exit, the exit number is
  in the middle of the roundabout picture, the "Then" chip shows the next roundabout's exit, and
  exit numbers now match the signs (Millbrook is the 2nd exit).
- **Bigger road names in the turn banner:** "At the end of the road, turn left" small, and
  "Burgess Road" big underneath.
- **Speed limits show much sooner,** usually within a second or two, even on roads you've never
  driven, and roads you've driven before are remembered.
- **Calmer camera warnings:** one warning per camera, no warnings for cameras on the other side
  of a dual carriageway, and a row of motorway cameras is said once.
- **Live traffic on the way:** every few minutes Kite checks the traffic again, so your arrival
  time stays right, and if a much faster way turns up it takes it and tells you.
- **Better voice:** road names said in full ("Road", not "Rd"), "a quarter of a
  mile", "Continue on M27 for 7 miles" on long stretches, motorways by their number, which side
  your destination is on, and quieter while you're on a phone call.
- **Trips end on their own:** parking a little short of the pin counts as arriving, and the trip
  closes a few minutes later so your battery isn't drained.
- **No more "Rerouting" in a car park** before you've reached the road, and asking for directions
  while driving gives routes that go the way you're facing.
- **British words:** "slip road", "car park", and search puts the place you typed first (no more
  The Body Shop above Whiteley Shopping Centre).
- **Trip list while driving** starts at your next turn, and route time bubbles are a little bigger.
- **Tidier Settings, like Waze:** a short menu of big rows (Voice & driving, Cameras & speed, Road
  alerts, Map & display, My places, Privacy, About), each opening its own page. Every setting is
  still there and works the same.

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
- **Clearer lane words:** two good lanes out of three on the left now say "Left 2 lanes" instead
  of "Middle lanes" (and the same on the right).
- **Fixes from a last check of the basics:**
  - Kite no longer says "You have arrived" twice at the end of a trip.
  - The step list shows the full length of each step, now that quiet name changes are hidden.
  - The wrong-way check looks at the road over about 30 m, so a tiny kink in the line can't make
    it think you're going backwards.
- **Smaller camera warning:** the speed camera card is about half the size, Waze-style, so it no
  longer hides the road. It's still bright orange (red when you're too fast) and pulses when it appears.
- **See more road ahead:** while driving, your arrow sits lower on the screen, so most of the map
  shows what's coming instead of what you've already passed.
- **Police heat map fixes:** its key ("Police ... often") is back above the risk chip, the glow
  now sits under your route, cameras and road names instead of covering them, and reports found
  in the background show on it straight away.
- **Waze is barely working, and Kite says so:** Waze's live map has stopped sharing drivers'
  reports (police, crashes, hazards); only road closures still come through. Settings › Waze
  integration now says this, greys out the switches that can't work, and the police heat map and
  risk score stay off until Waze shares reports again.
- **See what Waze sends:** Settings › Waze integration now has a "Last answer" line, like
  "3 police · 5 hazards · 140 road closures", so it's easy to tell if Waze stops sending a kind.
- **Google's route on motorway drives:** Google folds the long motorway part of a trip ("Merge
  onto M27", 12 km) into a step with no words, and Kite missed it. Its turns then looked out of
  place, so Kite dropped Google's traffic-aware route for the open map's. Now it reads that step,
  so drives like Park Gate to Southampton on the M27 follow Google's route.
- **"Open in Google Maps" for any place:** a dropped pin, a postcode or a shared spot opened
  OpenStreetMap's website instead. It now opens Google Maps at that spot, and Share sends a Google
  Maps link too.
- **Tidier step words:** "So'ton" instead of "So&#39;ton".
- **Search results in Google's order:** results now come in the same order as Google Maps, best
  match first, instead of nearest first (which could put a fuel company's office above real petrol
  stations). Sort › Distance still lists them nearest first.

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
