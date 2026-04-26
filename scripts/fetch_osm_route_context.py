#!/usr/bin/env python3
"""Fetch a compact OSM route-corridor context bundle for a GPX route.

This is a reversible pilot:
- no Android app changes
- no new app permissions
- no network use in the shipped app

It queries Overpass for:
- route-adjacent highway ways
- junction/control nodes near the route
- selected bike-useful POIs near the route

Output is a compact JSON bundle keyed by route progress.
"""

from __future__ import annotations

import argparse
import json
import math
import sys
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Sequence


EARTH_RADIUS_M = 6_371_000.0
DEFAULT_OVERPASS_URL = "https://overpass-api.de/api/interpreter"


@dataclass(frozen=True)
class LatLon:
    lat: float
    lon: float


@dataclass(frozen=True)
class XYPoint:
    x: float
    y: float


@dataclass(frozen=True)
class RouteProjection:
    progress_m: float
    offset_m: float
    side: str


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Fetch route-adjacent OSM context for a GPX route.",
    )
    parser.add_argument("gpx_path", type=Path, help="Input GPX file")
    parser.add_argument(
        "--out",
        type=Path,
        help="Output JSON path (default: alongside GPX with .osm-context.json suffix)",
    )
    parser.add_argument(
        "--overpass-url",
        default=DEFAULT_OVERPASS_URL,
        help=f"Overpass interpreter URL (default: {DEFAULT_OVERPASS_URL})",
    )
    parser.add_argument(
        "--sample-step-m",
        type=float,
        default=100.0,
        help="Minimum sampling step for the route polyline used in Overpass queries",
    )
    parser.add_argument(
        "--highway-radius-m",
        type=float,
        default=60.0,
        help="Corridor radius for highway/junction fetches",
    )
    parser.add_argument(
        "--poi-radius-m",
        type=float,
        default=250.0,
        help="Corridor radius for water/picnic/shelter/toilet POIs",
    )
    parser.add_argument(
        "--service-radius-m",
        type=float,
        default=700.0,
        help="Corridor radius for bike service POIs like shops/repair stations",
    )
    parser.add_argument(
        "--overpass-timeout-s",
        type=int,
        default=90,
        help="Overpass server-side timeout in seconds",
    )
    parser.add_argument(
        "--request-timeout-s",
        type=int,
        default=120,
        help="HTTP request timeout in seconds",
    )
    parser.add_argument(
        "--request-pause-s",
        type=float,
        default=1.0,
        help="Pause between Overpass sub-queries",
    )
    parser.add_argument(
        "--max-query-points",
        type=int,
        default=200,
        help="Maximum number of sampled route points per Overpass sub-query",
    )
    parser.add_argument(
        "--max-query-chunks",
        type=int,
        default=5,
        help="Soft cap for number of Overpass sub-queries per dataset type",
    )
    parser.add_argument(
        "--max-retries",
        type=int,
        default=4,
        help="Retries for transient Overpass errors like 429/504",
    )
    return parser.parse_args()


def require_points(route: Sequence[LatLon]) -> Sequence[LatLon]:
    if len(route) < 2:
        raise SystemExit("GPX route must contain at least two track points")
    return route


def parse_gpx_track_points(gpx_path: Path) -> list[LatLon]:
    root = ET.parse(gpx_path).getroot()
    track_points: list[LatLon] = []
    for element in root.iter():
        tag = element.tag.rsplit("}", 1)[-1]
        if tag != "trkpt":
            continue
        lat = element.attrib.get("lat")
        lon = element.attrib.get("lon")
        if lat is None or lon is None:
            continue
        track_points.append(LatLon(float(lat), float(lon)))
    return track_points


def meters_per_degree_lon(lat_deg: float) -> float:
    return (math.pi / 180.0) * EARTH_RADIUS_M * math.cos(math.radians(lat_deg))


def project_local(points: Sequence[LatLon]) -> tuple[list[XYPoint], LatLon]:
    anchor = points[0]
    lat_scale = (math.pi / 180.0) * EARTH_RADIUS_M
    lon_scale = meters_per_degree_lon(anchor.lat)
    projected = [
        XYPoint(
            (point.lon - anchor.lon) * lon_scale,
            (point.lat - anchor.lat) * lat_scale,
        )
        for point in points
    ]
    return projected, anchor


def cumulative_lengths(points_xy: Sequence[XYPoint]) -> list[float]:
    lengths = [0.0]
    for index in range(1, len(points_xy)):
        dx = points_xy[index].x - points_xy[index - 1].x
        dy = points_xy[index].y - points_xy[index - 1].y
        lengths.append(lengths[-1] + math.hypot(dx, dy))
    return lengths


def interpolate_point(start: LatLon, end: LatLon, fraction: float) -> LatLon:
    return LatLon(
        lat=start.lat + (end.lat - start.lat) * fraction,
        lon=start.lon + (end.lon - start.lon) * fraction,
    )


def resample_route(points: Sequence[LatLon], step_m: float) -> list[LatLon]:
    if step_m <= 0:
        raise ValueError("sample step must be positive")
    points = require_points(points)
    projected, _ = project_local(points)
    lengths = cumulative_lengths(projected)
    total_length = lengths[-1]
    if total_length <= step_m:
        return [points[0], points[-1]]

    targets = [0.0]
    cursor = step_m
    while cursor < total_length:
        targets.append(cursor)
        cursor += step_m
    targets.append(total_length)

    sampled: list[LatLon] = []
    segment_index = 0
    for target in targets:
        while segment_index + 1 < len(lengths) and lengths[segment_index + 1] < target:
            segment_index += 1
        if target <= lengths[segment_index]:
            sampled.append(points[segment_index])
            continue
        next_length = lengths[segment_index + 1]
        span = next_length - lengths[segment_index]
        if span <= 0:
            sampled.append(points[segment_index])
            continue
        fraction = (target - lengths[segment_index]) / span
        sampled.append(interpolate_point(points[segment_index], points[segment_index + 1], fraction))

    deduped: list[LatLon] = []
    for point in sampled:
        if not deduped or point != deduped[-1]:
            deduped.append(point)
    return deduped


def effective_sample_step(
    *,
    route_length_m: float,
    minimum_step_m: float,
    max_query_points: int,
    max_query_chunks: int,
) -> float:
    target_points = max_query_points * max_query_chunks
    if target_points <= 2 or route_length_m <= 0:
        return minimum_step_m
    adaptive_step = route_length_m / max(1, target_points - 1)
    return max(minimum_step_m, adaptive_step)


def format_polyline(points: Sequence[LatLon]) -> str:
    return ",".join(f"{point.lat:.7f},{point.lon:.7f}" for point in points)


def chunk_points(points: Sequence[LatLon], max_points: int) -> list[list[LatLon]]:
    if max_points < 2:
        raise ValueError("max query points must be at least 2")
    if len(points) <= max_points:
        return [list(points)]
    chunks: list[list[LatLon]] = []
    start = 0
    while start < len(points) - 1:
        end = min(start + max_points, len(points))
        chunk = list(points[start:end])
        chunks.append(chunk)
        if end == len(points):
            break
        start = end - 1
    return chunks


def overpass_query(
    *,
    url: str,
    timeout_s: int,
    request_timeout_s: int,
    query: str,
    max_retries: int,
) -> dict:
    del timeout_s  # encoded in the Overpass query itself
    payload = query.encode("utf-8")
    headers = {
        "Content-Type": "text/plain; charset=utf-8",
        "Accept": "application/json",
        "User-Agent": "GeePee-OSM-Pilot/1.0",
    }
    attempt = 0
    while True:
        request = urllib.request.Request(
            url,
            data=payload,
            method="POST",
            headers=headers,
        )
        try:
            with urllib.request.urlopen(request, timeout=request_timeout_s) as response:
                return json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as error:
            if error.code not in {429, 502, 503, 504} or attempt >= max_retries:
                raise
            retry_delay_s = min(20.0, 2.0 * (attempt + 1))
            print(
                f"[osm-context] transient Overpass error {error.code}; retrying in {retry_delay_s:.0f}s…",
                file=sys.stderr,
            )
            time.sleep(retry_delay_s)
            attempt += 1


def build_highway_query(polyline: str, radius_m: float, timeout_s: int) -> str:
    return f"""
[out:json][timeout:{timeout_s}];
(
  way[highway](around:{radius_m:.0f},{polyline});
);
out body geom;
""".strip()


def build_control_query(polyline: str, radius_m: float, timeout_s: int) -> str:
    return f"""
[out:json][timeout:{timeout_s}];
(
  node[highway~"^(traffic_signals|stop|crossing|mini_roundabout)$"](around:{radius_m:.0f},{polyline});
  node[railway="level_crossing"](around:{radius_m:.0f},{polyline});
  node[ford](around:{radius_m:.0f},{polyline});
  node[barrier](around:{radius_m:.0f},{polyline});
);
out body;
""".strip()


def build_poi_query(
    polyline: str,
    *,
    poi_radius_m: float,
    service_radius_m: float,
    timeout_s: int,
) -> str:
    return f"""
[out:json][timeout:{timeout_s}];
(
  nwr[amenity="drinking_water"](around:{poi_radius_m:.0f},{polyline});
  nwr[tourism="picnic_site"](around:{poi_radius_m:.0f},{polyline});
  nwr[amenity="shelter"](around:{poi_radius_m:.0f},{polyline});
  nwr[amenity="toilets"](around:{poi_radius_m:.0f},{polyline});
  nwr[shop="bicycle"](around:{service_radius_m:.0f},{polyline});
  nwr[amenity="bicycle_repair_station"](around:{service_radius_m:.0f},{polyline});
);
out center tags;
""".strip()


def route_projection(point: LatLon, route_ll: Sequence[LatLon]) -> RouteProjection:
    route_xy, anchor = project_local(route_ll)
    point_xy = project_local([anchor, point])[0][1]
    lengths = cumulative_lengths(route_xy)

    best_distance = math.inf
    best_progress = 0.0
    best_cross = 0.0

    for index in range(len(route_xy) - 1):
        start = route_xy[index]
        end = route_xy[index + 1]
        dx = end.x - start.x
        dy = end.y - start.y
        seg_len_sq = dx * dx + dy * dy
        if seg_len_sq <= 0:
            continue
        rel_x = point_xy.x - start.x
        rel_y = point_xy.y - start.y
        fraction = max(0.0, min(1.0, (rel_x * dx + rel_y * dy) / seg_len_sq))
        proj_x = start.x + dx * fraction
        proj_y = start.y + dy * fraction
        off_x = point_xy.x - proj_x
        off_y = point_xy.y - proj_y
        distance = math.hypot(off_x, off_y)
        if distance < best_distance:
            best_distance = distance
            best_progress = lengths[index] + math.hypot(proj_x - start.x, proj_y - start.y)
            best_cross = dx * rel_y - dy * rel_x

    side = "on"
    if best_distance > 0.5:
        side = "left" if best_cross > 0 else "right"
    return RouteProjection(progress_m=best_progress, offset_m=best_distance, side=side)


def kind_for_poi(tags: dict[str, str]) -> str:
    if tags.get("amenity") == "drinking_water":
        return "drinking_water"
    if tags.get("tourism") == "picnic_site":
        return "picnic_site"
    if tags.get("amenity") == "shelter":
        return "shelter"
    if tags.get("amenity") == "toilets":
        return "toilets"
    if tags.get("shop") == "bicycle":
        return "bicycle_shop"
    if tags.get("amenity") == "bicycle_repair_station":
        return "bicycle_repair_station"
    return "unknown"


def filtered_way_tags(tags: dict[str, str]) -> dict[str, str]:
    keep = (
        "name",
        "ref",
        "highway",
        "surface",
        "smoothness",
        "tracktype",
        "bicycle",
        "cycleway",
        "cycleway:left",
        "cycleway:right",
        "lit",
        "access",
        "oneway",
        "junction",
    )
    return {key: value for key, value in tags.items() if key in keep}


def filtered_poi_tags(tags: dict[str, str]) -> dict[str, str]:
    keep = (
        "name",
        "amenity",
        "tourism",
        "shop",
        "operator",
        "access",
        "covered",
        "drinking_water",
        "bottle",
        "shelter_type",
        "toilets:access",
    )
    return {key: value for key, value in tags.items() if key in keep}


def load_way_geometries(data: dict) -> list[dict]:
    ways: list[dict] = []
    for element in data.get("elements", []):
        if element.get("type") != "way":
            continue
        geometry = [
            {"lat": point["lat"], "lon": point["lon"]}
            for point in element.get("geometry", [])
            if "lat" in point and "lon" in point
        ]
        if len(geometry) < 2:
            continue
        ways.append(
            {
                "id": element["id"],
                "node_ids": element.get("nodes", []),
                "tags": filtered_way_tags(element.get("tags", {})),
                "geometry": geometry,
            }
        )
    return ways


def load_control_nodes(data: dict) -> dict[int, dict]:
    controls: dict[int, dict] = {}
    for element in data.get("elements", []):
        if element.get("type") != "node":
            continue
        tags = element.get("tags", {})
        controls[element["id"]] = {
            "id": element["id"],
            "lat": element["lat"],
            "lon": element["lon"],
            "kind": tags.get("highway") or tags.get("railway") or tags.get("barrier") or "ford",
            "tags": tags,
        }
    return controls


def way_node_memberships(ways: Sequence[dict]) -> dict[int, list[int]]:
    memberships: dict[int, list[int]] = {}
    for way in ways:
        for node_id in way["node_ids"]:
            memberships.setdefault(node_id, []).append(way["id"])
    return memberships


def way_node_neighbors(ways: Sequence[dict]) -> dict[int, set[int]]:
    neighbors: dict[int, set[int]] = {}
    for way in ways:
        node_ids = way["node_ids"]
        for index, node_id in enumerate(node_ids):
            branch_neighbors = neighbors.setdefault(node_id, set())
            if index > 0:
                branch_neighbors.add(node_ids[index - 1])
            if index + 1 < len(node_ids):
                branch_neighbors.add(node_ids[index + 1])
    return neighbors


def derive_junctions(
    *,
    ways: Sequence[dict],
    controls: dict[int, dict],
    route_points: Sequence[LatLon],
    highway_radius_m: float,
) -> list[dict]:
    way_lookup = {way["id"]: way for way in ways}
    memberships = way_node_memberships(ways)
    neighbors = way_node_neighbors(ways)
    node_coords: dict[int, LatLon] = {}
    for way in ways:
        for node_id, point in zip(way["node_ids"], way["geometry"], strict=False):
            node_coords.setdefault(node_id, LatLon(point["lat"], point["lon"]))

    junctions: list[dict] = []
    for node_id, way_ids in memberships.items():
        branch_count = len(neighbors.get(node_id, set()))
        if branch_count < 3 and node_id not in controls:
            continue
        point = node_coords.get(node_id)
        if point is None:
            continue
        projection = route_projection(point, route_points)
        if projection.offset_m > highway_radius_m + 20.0:
            continue
        highway_values = sorted(
            {
                way_lookup[way_id]["tags"].get("highway", "")
                for way_id in set(way_ids)
                if way_id in way_lookup
            }
            - {""}
        )
        control = controls.get(node_id)
        junctions.append(
            {
                "id": node_id,
                "lat": point.lat,
                "lon": point.lon,
                "route_progress_m": round(projection.progress_m, 1),
                "offset_m": round(projection.offset_m, 1),
                "connected_way_ids": sorted(set(way_ids)),
                "branch_count": branch_count,
                "highway_values": highway_values,
                "control_kind": control["kind"] if control else None,
            }
        )

    junctions.sort(key=lambda item: (item["route_progress_m"], item["id"]))
    deduped: list[dict] = []
    seen: set[tuple[int, tuple[int, ...], str | None]] = set()
    for junction in junctions:
        key = (
            int(junction["route_progress_m"] * 10),
            tuple(junction["connected_way_ids"]),
            junction["control_kind"],
        )
        if key in seen:
            continue
        seen.add(key)
        deduped.append(junction)
    return deduped


def extract_poi_position(element: dict) -> LatLon | None:
    if "lat" in element and "lon" in element:
        return LatLon(element["lat"], element["lon"])
    center = element.get("center")
    if center and "lat" in center and "lon" in center:
        return LatLon(center["lat"], center["lon"])
    return None


def load_pois(data: dict, route_points: Sequence[LatLon]) -> list[dict]:
    pois: list[dict] = []
    for element in data.get("elements", []):
        tags = element.get("tags", {})
        kind = kind_for_poi(tags)
        if kind == "unknown":
            continue
        position = extract_poi_position(element)
        if position is None:
            continue
        projection = route_projection(position, route_points)
        pois.append(
            {
                "id": element["id"],
                "osm_type": element.get("type", "unknown"),
                "kind": kind,
                "name": tags.get("name"),
                "lat": position.lat,
                "lon": position.lon,
                "route_progress_m": round(projection.progress_m, 1),
                "offset_m": round(projection.offset_m, 1),
                "side": projection.side,
                "tags": filtered_poi_tags(tags),
            }
        )
    pois.sort(key=lambda item: (item["route_progress_m"], item["kind"], item["id"]))
    return pois


def default_output_path(gpx_path: Path) -> Path:
    return gpx_path.with_suffix(".osm-context.json")


def query_with_summary(label: str, query: str, args: argparse.Namespace) -> dict:
    print(f"[osm-context] querying {label}…", file=sys.stderr)
    start = time.time()
    data = overpass_query(
        url=args.overpass_url,
        timeout_s=args.overpass_timeout_s,
        request_timeout_s=args.request_timeout_s,
        query=query,
        max_retries=args.max_retries,
    )
    elapsed = time.time() - start
    print(
        f"[osm-context] {label}: {len(data.get('elements', []))} elements in {elapsed:.1f}s",
        file=sys.stderr,
    )
    return data


def merge_overpass_elements(chunks: Iterable[dict]) -> dict:
    merged: dict[tuple[str, int], dict] = {}
    for chunk in chunks:
        for element in chunk.get("elements", []):
            key = (element.get("type", "unknown"), element["id"])
            merged[key] = element
    return {"elements": list(merged.values())}


def query_chunks(
    label: str,
    sampled_points: Sequence[LatLon],
    args: argparse.Namespace,
    build_query,
) -> dict:
    chunks = chunk_points(sampled_points, args.max_query_points)
    if len(chunks) == 1:
        return query_with_summary(label, build_query(format_polyline(chunks[0])), args)

    merged_chunks: list[dict] = []
    total_elements = 0
    started = time.time()
    for index, chunk in enumerate(chunks, start=1):
        print(
            f"[osm-context] querying {label} chunk {index}/{len(chunks)}…",
            file=sys.stderr,
        )
        data = overpass_query(
            url=args.overpass_url,
            timeout_s=args.overpass_timeout_s,
            request_timeout_s=args.request_timeout_s,
            query=build_query(format_polyline(chunk)),
            max_retries=args.max_retries,
        )
        element_count = len(data.get("elements", []))
        total_elements += element_count
        print(
            f"[osm-context] {label} chunk {index}/{len(chunks)}: {element_count} elements",
            file=sys.stderr,
        )
        merged_chunks.append(data)
        if index < len(chunks) and args.request_pause_s > 0:
            time.sleep(args.request_pause_s)

    merged = merge_overpass_elements(merged_chunks)
    elapsed = time.time() - started
    print(
        f"[osm-context] {label}: {len(merged.get('elements', []))} unique elements "
        f"({total_elements} raw) in {elapsed:.1f}s",
        file=sys.stderr,
    )
    return merged


def summarize_bundle(bundle: dict) -> str:
    return (
        f"{len(bundle['highways'])} ways, "
        f"{len(bundle['junctions'])} junctions, "
        f"{len(bundle['pois'])} POIs"
    )


def main() -> None:
    args = parse_args()
    route_points = require_points(parse_gpx_track_points(args.gpx_path))
    route_xy, _ = project_local(route_points)
    route_length_m = cumulative_lengths(route_xy)[-1]
    sample_step_m = effective_sample_step(
        route_length_m=route_length_m,
        minimum_step_m=args.sample_step_m,
        max_query_points=args.max_query_points,
        max_query_chunks=args.max_query_chunks,
    )
    sampled_points = resample_route(route_points, sample_step_m)

    highway_data = query_chunks(
        "highways",
        sampled_points,
        args,
        lambda polyline: build_highway_query(polyline, args.highway_radius_m, args.overpass_timeout_s),
    )
    control_data = query_chunks(
        "controls",
        sampled_points,
        args,
        lambda polyline: build_control_query(polyline, args.highway_radius_m, args.overpass_timeout_s),
    )
    poi_data = query_chunks(
        "pois",
        sampled_points,
        args,
        lambda polyline: build_poi_query(
            polyline,
            poi_radius_m=args.poi_radius_m,
            service_radius_m=args.service_radius_m,
            timeout_s=args.overpass_timeout_s,
        ),
    )

    highways = load_way_geometries(highway_data)
    controls = load_control_nodes(control_data)
    junctions = derive_junctions(
        ways=highways,
        controls=controls,
        route_points=route_points,
        highway_radius_m=args.highway_radius_m,
    )
    pois = load_pois(poi_data, route_points)

    bundle = {
        "attribution": "© OpenStreetMap contributors",
        "license": "ODbL-1.0",
        "source": {
            "provider": "Overpass API",
            "url": args.overpass_url,
        },
        "route": {
            "name": args.gpx_path.name,
            "track_point_count": len(route_points),
            "query_point_count": len(sampled_points),
            "length_m": round(route_length_m, 1),
        },
        "corridor": {
            "highway_radius_m": args.highway_radius_m,
            "poi_radius_m": args.poi_radius_m,
            "service_radius_m": args.service_radius_m,
            "sample_step_m": round(sample_step_m, 1),
            "requested_min_sample_step_m": args.sample_step_m,
            "max_query_points": args.max_query_points,
            "max_query_chunks": args.max_query_chunks,
            "request_pause_s": args.request_pause_s,
            "fetched_at_epoch_s": int(time.time()),
        },
        "highways": highways,
        "junctions": junctions,
        "pois": pois,
    }

    out_path = args.out or default_output_path(args.gpx_path)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(bundle, indent=2, sort_keys=False) + "\n", encoding="utf-8")
    print(f"[osm-context] wrote {out_path}", file=sys.stderr)
    print(f"[osm-context] summary: {summarize_bundle(bundle)}", file=sys.stderr)


if __name__ == "__main__":
    try:
        main()
    except urllib.error.HTTPError as error:
        body = error.read().decode("utf-8", errors="replace")
        raise SystemExit(f"Overpass HTTP error {error.code}: {body.strip()}") from error
    except urllib.error.URLError as error:
        raise SystemExit(f"Network error: {error}") from error
