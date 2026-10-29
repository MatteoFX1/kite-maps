# Lane arrows OpenStreetMap maps on the roads into some roundabouts (turn:lanes), for the lane advice.
import json, sys, urllib.request, urllib.parse, time
UA = {"User-Agent": "KiteMaps-iOS-probe/0.1 (+https://github.com/matteofx1/kite-maps)"}
spots = [("M27 J3 from the M271", 50.94496, -1.47631), ("M27 J5 Stoneham", 50.94892, -1.37182),
         ("M3 J14 Chilworth", 50.95511, -1.35678), ("M27 J7 Hedge End", 50.91751, -1.31867),
         ("M27 J11 Fareham", 50.86073, -1.15789), ("Basingstoke M3 J6", 51.26351, -1.06632),
         ("M3 J12 Chandler's Ford", 50.99782, -1.35815), ("M4 J28 Newport", 51.56801, -3.02184)]
for name, lat, lng in spots:
    q = '[out:json][timeout:60];way(around:250,%f,%f)[highway][~"^(lanes|turn:lanes.*|lanes:forward|lanes:backward)$"~"."];out tags center;' % (lat, lng)
    els = []
    for host in ["https://overpass-api.de/api/interpreter", "https://overpass.private.coffee/api/interpreter", "https://maps.mail.ru/osm/tools/overpass/api/interpreter"]:
        try:
            els = json.load(urllib.request.urlopen(urllib.request.Request(host, data=urllib.parse.urlencode({"data": q}).encode(), headers=UA), timeout=90))["elements"]
            break
        except Exception as e:
            print("overpass failed", host, e)
    print("=== %s (%.5f,%.5f): %d ways" % (name, lat, lng, len(els)))
    for e in els:
        t = e.get("tags", {})
        keep = {k: v for k, v in t.items() if "lanes" in k or k in ("oneway", "junction", "ref")}
        print("   way %s %s %s %.5f,%.5f" % (e["id"], t.get("highway"), keep, e["center"]["lat"], e["center"]["lon"]))
    time.sleep(2)
