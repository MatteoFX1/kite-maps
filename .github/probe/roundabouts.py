# Dumps what OSRM and Overpass say about the roundabouts on a few UK drives, so the
# roundabout picture can be checked against real data. Run by the build workflow.
import base64, gzip, json, urllib.request, urllib.parse

UA = {"User-Agent": "KiteMaps-iOS-probe/0.1 (+https://github.com/matteofx1/kite-maps)"}

def get(url, data=None):
    req = urllib.request.Request(url, data=data, headers=UA)
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.load(r)

drives = [
    ("Park Gate to Portsmouth", (50.8655, -1.2800), (50.8000, -1.0900)),
    ("Park Gate to Southampton", (50.8655, -1.2800), (50.9090, -1.4040)),
    ("Fareham to Park Gate", (50.8520, -1.1790), (50.8655, -1.2800)),
    ("Whiteley to Southampton", (50.8850, -1.2440), (50.9090, -1.4040)),
    ("Whiteley to Portsmouth", (50.8850, -1.2440), (50.8000, -1.0900)),
    ("Swindon Magic Roundabout", (51.5640, -1.7700), (51.5600, -1.7790)),
]
spots = []
raw = {"osrm": {}, "overpass": None}
for name, a, b in drives:
    url = ("https://routing.openstreetmap.de/routed-car/route/v1/driving/%f,%f;%f,%f"
           "?overview=false&geometries=polyline6&steps=true" % (a[1], a[0], b[1], b[0]))
    try:
        r = get(url)
    except Exception as e:
        print("OSRM failed", name, e); continue
    raw["osrm"][name] = r
    steps = r["routes"][0]["legs"][0]["steps"]
    print("=== %s: %d steps" % (name, len(steps)))
    for i, s in enumerate(steps):
        m = s["maneuver"]
        if m["type"] not in ("roundabout", "rotary", "roundabout turn"):
            continue
        print("-- step %d %s exit=%s name=%r ref=%r loc=%s before=%s after=%s dist=%.0f" % (
            i, m["type"], m.get("exit"), s.get("name"), s.get("ref"), m["location"],
            m.get("bearing_before"), m.get("bearing_after"), s["distance"]))
        print("   geometry", s["geometry"])
        for j, x in enumerate(s["intersections"]):
            print("   int %d loc=%s bearings=%s entry=%s in=%s out=%s lanes=%s" % (
                j, x["location"], x["bearings"], x["entry"], x.get("in"), x.get("out"),
                [(l["indications"], l["valid"]) for l in x.get("lanes", [])]))
        if i + 1 < len(steps):
            n = steps[i + 1]
            print("   next %s %s name=%r" % (n["maneuver"]["type"], n["maneuver"].get("modifier"), n.get("name")))
            for j, x in enumerate(n["intersections"][:2]):
                print("   next int %d loc=%s bearings=%s entry=%s in=%s out=%s" % (
                    j, x["location"], x["bearings"], x["entry"], x.get("in"), x.get("out")))
        if i > 0:
            p = steps[i - 1]
            for j, x in enumerate(p["intersections"][-3:]):
                print("   prev int loc=%s bearings=%s entry=%s in=%s out=%s lanes=%s" % (
                    x["location"], x["bearings"], x["entry"], x.get("in"), x.get("out"),
                    [(l["indications"], l["valid"]) for l in x.get("lanes", [])]))
        spots.append(m["location"])

if spots:
    around = "".join("way[junction~\"^(roundabout|circular)$\"](around:40,%f,%f);" % (s[1], s[0]) for s in spots)
    q = ("[out:json][timeout:25];(%s)->.r;.r out geom;node(w.r)->.n;"
         "way(bn.n)[highway][highway!~\"^(footway|cycleway|path|pedestrian|steps|bridleway)$\"];out geom;" % around)
    for host in ["https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter"]:
        try:
            o = get(host, data=urllib.parse.urlencode({"data": q}).encode())
        except Exception as e:
            print("Overpass failed", host, e); continue
        raw["overpass"] = o
        print("=== Overpass %s: %d elements" % (host, len(o["elements"])))
        for e in o["elements"]:
            t = e.get("tags", {})
            if t.get("junction") or t.get("turn:lanes"):
                print("way %s %s junction=%s oneway=%s ref=%r nodes=%d turn:lanes=%s" % (
                    e["id"], t.get("highway"), t.get("junction"), t.get("oneway"), t.get("ref"),
                    len(e.get("nodes", [])), t.get("turn:lanes")))
        break

# Everything, packed, for building test fixtures from.
blob = base64.b64encode(gzip.compress(json.dumps(raw, separators=(",", ":")).encode(), 9)).decode()
print("RAWDATA-BEGIN")
for i in range(0, len(blob), 4000):
    print(blob[i:i + 4000])
print("RAWDATA-END")
