# Prints the lanes OSRM gives on the way into each roundabout of a few UK drives, with how far
# before the roundabout each one sits, so the lane guidance can be checked against real data.
import json, math, urllib.request

UA = {"User-Agent": "KiteMaps-iOS-probe/0.1 (+https://github.com/matteofx1/kite-maps)"}

drives = [
    ("M271 to Redbridge roundabout, 1st exit A33", (50.9890, -1.4970), (50.9077, -1.4136)),
    ("M271 to Redbridge roundabout, to Totton", (50.9890, -1.4970), (50.9190, -1.4900)),
    ("Park Gate to Southampton", (50.8655, -1.2800), (50.9090, -1.4040)),
    ("Whiteley to Portsmouth", (50.8850, -1.2440), (50.8000, -1.0900)),
]

def dist(a, b):
    k = math.cos(math.radians(a[1]))
    return math.hypot((a[0] - b[0]) * 111320 * k, (a[1] - b[1]) * 110540)

def lanes(x):
    return " | ".join(("*" if l["valid"] else "") + "/".join(l["indications"]) for l in x.get("lanes", []))

for name, a, b in drives:
    url = ("https://routing.openstreetmap.de/routed-car/route/v1/driving/%f,%f;%f,%f"
           "?overview=false&steps=true" % (a[1], a[0], b[1], b[0]))
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60) as r:
            steps = json.load(r)["routes"][0]["legs"][0]["steps"]
    except Exception as e:
        print("OSRM failed", name, e); continue
    print("=== %s" % name)
    for i, s in enumerate(steps):
        m = s["maneuver"]
        if m["type"] not in ("roundabout", "rotary"):
            continue
        at = m["location"]
        print("-- step %d exit=%s onto %r/%r at %s" % (i, m.get("exit"), s.get("name"), s.get("ref"), at))
        print("   entry lanes: %s" % (lanes(s["intersections"][0]) or "none"))
        if i > 0:
            p = steps[i - 1]
            print("   step before: %s %r, %.0f m" % (p["maneuver"]["type"], p.get("name") or p.get("ref"), p["distance"]))
            for x in p["intersections"]:
                if "lanes" in x:
                    print("     %5.0f m before: %s  (bearings %s, can enter %s, in %s, out %s)" % (
                        dist(x["location"], at), lanes(x), x.get("bearings"), x.get("entry"), x.get("in"), x.get("out")))
