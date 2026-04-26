# OSM Route Context Pilot

This is a reversible pilot for adding route-adjacent OpenStreetMap context to GeePee.

It is intentionally **not** wired into the Android app yet.

Current scope:
- fetch route-adjacent `highway=*` ways
- derive simple junctions from shared way nodes
- fetch selected bike-useful POIs:
  - `amenity=drinking_water`
  - `tourism=picnic_site`
  - `amenity=shelter`
  - `amenity=toilets`
  - `shop=bicycle`
  - `amenity=bicycle_repair_station`

The output is a compact JSON bundle keyed by route progress.

## Why this shape

GeePee does not need full raster/vector maps for a first pass.

It needs:
- nearby branches at route decision points
- route-relevant junction structure
- a short list of useful POIs ahead/behind

So the pilot keeps the app untouched and builds a route corridor extract off-device first.

## Usage

Example using the checked-in Tisza route:

```bash
./scripts/fetch_osm_route_context.py \
  ./routes/unneplos-tisza-ride.gpx \
  --out /tmp/unneplos-tisza-ride.osm-context.json
```

The defaults are:
- route/highway corridor radius: `60 m`
- general POI radius: `250 m`
- bike-service radius: `700 m`
- minimum query polyline sampling: `100 m`

For long routes, the script automatically coarsens the query polyline to stay within a small number of Overpass sub-queries.

You can tighten or widen them:

```bash
./scripts/fetch_osm_route_context.py \
  ./routes/unneplos-tisza-ride.gpx \
  --highway-radius-m 40 \
  --poi-radius-m 200 \
  --service-radius-m 1000 \
  --sample-step-m 100
```

## Output shape

Top-level keys:
- `route`
- `corridor`
- `highways`
- `junctions`
- `pois`

Notes:
- `highways` keeps only a small tag subset useful for route context
- `junctions` are derived from returned ways, because many OSM junctions are just shared nodes
- `pois` are snapped to route progress and include `offset_m` and `side`

## Limits

- This uses public Overpass and is suitable for development/pilot use, not phone-scale production traffic.
- Large/complex routes may need chunking or stronger simplification later.
- OSM tagging varies by region, so POI completeness is uneven.

## Licensing

Any future in-app use should include visible attribution:

- `© OpenStreetMap contributors`

OSM data is under ODbL. See:
- https://osmfoundation.org/wiki/Licence/Attribution_Guidelines
- https://wiki.openstreetmap.org/wiki/Open_Data_License/Use_Cases
