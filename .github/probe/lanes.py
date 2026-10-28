# Raw lane data around each roundabout on a route: what the open router says, and what OpenStreetMap maps.
import json, sys, urllib.request, urllib.parse, time
UA = {"User-Agent": "KiteMaps-iOS-probe/0.1 (+https://github.com/matteofx1/kite-maps)"}
a = [float(x) for x in sys.argv[1:5]]
url = "https://routing.openstreetmap.de/routed-car/route/v1/driving/%f,%f;%f,%f?steps=true&overview=false" % (a[1], a[0], a[3], a[2])
r = json.load(urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60))
steps = r["routes"][0]["legs"][0]["steps"]
spots = []
for i, s in enumerate(steps):
    m = s["maneuver"]
    if m["type"] in ("roundabout", "rotary", "exit roundabout", "exit rotary"):
        print("STEP %d %s %s exit=%s name=%r at %.5f,%.5f" % (i, m["type"], m.get("modifier"), m.get("exit"), s.get("name") or s.get("ref"), m["location"][1], m["location"][0]))
        for k in ([i - 1] if i > 0 else []) + [i]:
            for x in steps[k]["intersections"][-3:] if k < i else steps[k]["intersections"]:
                if "lanes" in x:
                    print("   step %d lanes at %.5f,%.5f: %s" % (k, x["location"][1], x["location"][0],
                          " | ".join(("*" if l["valid"] else "") + "/".join(l["indications"]) for l in x["lanes"])))
        if m["type"] in ("roundabout", "rotary"): spots.append(m["location"])
for lng, lat in spots:
    q = '[out:json][timeout:60];way(around:150,%f,%f)[highway][~"^(lanes|turn:lanes.*|lanes:forward)$"~"."];out tags center;' % (lat, lng)
    for host in ["https://overpass-api.de/api/interpreter", "https://overpass.private.coffee/api/interpreter", "https://maps.mail.ru/osm/tools/overpass/api/interpreter"]:
        try:
            els = json.load(urllib.request.urlopen(urllib.request.Request(host, data=urllib.parse.urlencode({"data": q}).encode(), headers=UA), timeout=90))["elements"]
            break
        except Exception as e:
            print("overpass failed", host, e); els = []
    print("OSM near %.5f,%.5f" % (lat, lng))
    for e in els:
        t = e.get("tags", {})
        keep = {k: v for k, v in t.items() if "lanes" in k or k in ("oneway", "junction", "highway")}
        print("   way %s %r %s %.5f,%.5f" % (e["id"], t.get("name") or t.get("ref"), keep, e["center"]["lat"], e["center"]["lon"]))
    time.sleep(2)
