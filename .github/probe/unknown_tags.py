# Tags of the roads the speed limit audit found without a limit, counted, so the cause shows.
import json, urllib.request, urllib.parse, collections
ids = [l.strip() for l in open("VelaCore/unknown-ways.txt") if l.strip() and l.strip() != "0"]
print("unknown ways:", len(ids))
UA = {"User-Agent": "KiteMaps-iOS-probe/0.1 (+https://github.com/matteofx1/kite-maps)"}
els = []
for i in range(0, len(ids), 400):
    q = "[out:json][timeout:60];way(id:%s);out tags center;" % ",".join(ids[i:i + 400])
    for host in ["https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter"]:
        try:
            req = urllib.request.Request(host, data=urllib.parse.urlencode({"data": q}).encode(), headers=UA)
            els += json.load(urllib.request.urlopen(req, timeout=90))["elements"]
            break
        except Exception as e:
            print("overpass failed", host, e)
keys = collections.Counter()
hw = collections.Counter()
for e in els:
    t = e.get("tags", {})
    hw[t.get("highway")] += 1
    for k in t:
        if "maxspeed" in k or k in ("lit", "zone:traffic", "source:maxspeed", "designation"):
            keys["%s=%s" % (k, t[k])] += 1
print("highway:", hw.most_common())
print("speed-related tags:", keys.most_common(40))
for e in els[:400]:
    t = e.get("tags", {})
    sp = {k: v for k, v in t.items() if "maxspeed" in k or k in ("lit",)}
    c = e.get("center", {})
    print("WAY %s %s %r %s %.5f,%.5f" % (e["id"], t.get("highway"), t.get("name") or t.get("ref"), sp, c.get("lat", 0), c.get("lon", 0)))
