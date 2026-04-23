#!/usr/bin/env python3
import argparse
import math
import random
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

EARTH_RADIUS_M = 6371000.0


def run(cmd):
    return subprocess.run(cmd, check=True, text=True, capture_output=True)


def haversine_m(lat1, lon1, lat2, lon2):
    p1 = math.radians(lat1)
    p2 = math.radians(lat2)
    dlat = math.radians(lat2 - lat1)
    dlon = math.radians(lon2 - lon1)
    a = math.sin(dlat / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dlon / 2) ** 2
    return 2 * EARTH_RADIUS_M * math.asin(math.sqrt(a))


def interpolate_point(p1, p2, fraction):
    return (
        p1[0] + (p2[0] - p1[0]) * fraction,
        p1[1] + (p2[1] - p1[1]) * fraction,
    )


def add_noise_m(lat, lon, sigma_m):
    if sigma_m <= 0:
        return lat, lon
    north_m = random.gauss(0.0, sigma_m)
    east_m = random.gauss(0.0, sigma_m)
    lat_per_m = 1.0 / 111_111.0
    lon_per_m = 1.0 / (111_111.0 * math.cos(math.radians(lat)))
    return lat + north_m * lat_per_m, lon + east_m * lon_per_m


def parse_gpx_points(path: Path):
    tree = ET.parse(path)
    root = tree.getroot()
    points = []
    for elem in root.iter():
        if elem.tag.endswith("trkpt"):
            points.append((float(elem.attrib["lat"]), float(elem.attrib["lon"])))
    if len(points) < 2:
        raise ValueError("GPX needs at least 2 track points")
    return points


def resample(points, spacing_m):
    sampled = [points[0]]
    carry = 0.0
    target = spacing_m

    for start, end in zip(points, points[1:]):
        seg_len = haversine_m(start[0], start[1], end[0], end[1])
        if seg_len <= 0.01:
            continue
        dist = carry
        while dist + seg_len >= target:
            fraction = (target - dist) / seg_len
            sampled.append(interpolate_point(start, end, fraction))
            target += spacing_m
        carry += seg_len
    if sampled[-1] != points[-1]:
        sampled.append(points[-1])
    return sampled


def setup_mock_provider(provider):
    run(["adb", "shell", "appops", "set", "2000", "android:mock_location", "allow"])
    run(["adb", "shell", "cmd", "location", "set-location-enabled", "true"])
    subprocess.run(
        ["adb", "shell", "cmd", "location", "providers", "remove-test-provider", provider],
        check=False,
        text=True,
        capture_output=True,
    )
    run(
        [
            "adb",
            "shell",
            "cmd",
            "location",
            "providers",
            "add-test-provider",
            provider,
            "--requiresSatellite",
            "--supportsAltitude",
            "--supportsSpeed",
            "--supportsBearing",
        ]
    )
    run(
        [
            "adb",
            "shell",
            "cmd",
            "location",
            "providers",
            "set-test-provider-enabled",
            provider,
            "true",
        ]
    )


def ensure_mock_provider(provider):
    run(["adb", "shell", "appops", "set", "2000", "android:mock_location", "allow"])
    run(
        [
            "adb",
            "shell",
            "cmd",
            "location",
            "providers",
            "set-test-provider-enabled",
            provider,
            "true",
        ]
    )


def remove_mock_provider(provider):
    subprocess.run(
        ["adb", "shell", "cmd", "location", "providers", "remove-test-provider", provider],
        check=False,
        text=True,
        capture_output=True,
    )
    subprocess.run(
        ["adb", "shell", "appops", "set", "2000", "android:mock_location", "deny"],
        check=False,
        text=True,
        capture_output=True,
    )


def send_location(provider, lat, lon, accuracy_m):
    cmd = [
        "adb",
        "shell",
        "cmd",
        "location",
        "providers",
        "set-test-provider-location",
        provider,
        "--location",
        f"{lat:.7f},{lon:.7f}",
        "--accuracy",
        f"{accuracy_m:.1f}",
    ]
    last_error = None
    for attempt in range(1, 6):
        try:
            ensure_mock_provider(provider)
            run(cmd)
            return
        except subprocess.CalledProcessError as error:
            last_error = error
            time.sleep(0.15 * attempt)
            if attempt == 3:
                setup_mock_provider(provider)
    stderr = (last_error.stderr or "").strip() if last_error else ""
    stdout = (last_error.stdout or "").strip() if last_error else ""
    raise RuntimeError(
        f"Failed to send mock location after retries for provider={provider}. "
        f"stdout={stdout!r} stderr={stderr!r}"
    )


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("gpx")
    parser.add_argument("--provider", default="gps")
    parser.add_argument("--period-s", type=float, default=1.0)
    parser.add_argument("--speed-mps", type=float, default=3.0)
    parser.add_argument("--noise-m", type=float, default=4.0)
    parser.add_argument("--accuracy-m", type=float, default=5.0)
    parser.add_argument("--points", type=int, default=120)
    parser.add_argument("--start-fraction", type=float, default=0.0)
    parser.add_argument("--start-index", type=int)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--keep-provider", action="store_true")
    args = parser.parse_args()

    random.seed(args.seed)
    points = parse_gpx_points(Path(args.gpx))
    spacing_m = max(0.5, args.speed_mps * args.period_s)
    sampled = resample(points, spacing_m)
    if not sampled:
        raise ValueError("No sampled points")

    if args.start_index is not None:
        start_index = args.start_index
    else:
        normalized_fraction = min(max(args.start_fraction, 0.0), 1.0)
        start_index = int(round(normalized_fraction * max(len(sampled) - 1, 0)))

    if start_index < 0 or start_index >= len(sampled):
        raise ValueError(f"start index {start_index} is outside sampled route of {len(sampled)} points")

    selected = sampled[start_index : start_index + args.points]
    if not selected:
        raise ValueError("No replay points selected from the requested route slice")
    print(
        f"Replaying {len(selected)} points from {args.gpx} starting at sampled point "
        f"{start_index + 1}/{len(sampled)} at {args.speed_mps:.1f} m/s, "
        f"{args.period_s:.1f}s interval, noise sigma {args.noise_m:.1f}m",
        file=sys.stderr,
    )

    setup_mock_provider(args.provider)
    try:
        for index, (lat, lon) in enumerate(selected):
            noisy_lat, noisy_lon = add_noise_m(lat, lon, args.noise_m)
            send_location(
                args.provider,
                noisy_lat,
                noisy_lon,
                args.accuracy_m,
            )
            print(f"{index+1:03d}: {noisy_lat:.7f},{noisy_lon:.7f}", file=sys.stderr)
            time.sleep(args.period_s)
    finally:
        if not args.keep_provider:
            remove_mock_provider(args.provider)


if __name__ == "__main__":
    main()
