# Mock Replay

Use `scripts/replay_gpx_mock.py` to drive GeePee along a GPX route on a real phone via `adb shell cmd location`.

Example using the checked-in Tisza route:

```bash
./scripts/replay_gpx_mock.py ./routes/unneplos-tisza-ride.gpx --points 35 --period-s 1.0 --speed-mps 2.2 --noise-m 2.0 --accuracy-m 4.0
```

Start later in the route:

```bash
./scripts/replay_gpx_mock.py ./routes/unneplos-tisza-ride.gpx --start-fraction 0.55 --points 35 --period-s 1.0 --speed-mps 2.2 --noise-m 2.0 --accuracy-m 4.0
```

Start-of-route hairpin check:

```bash
./scripts/replay_gpx_mock.py ./routes/unneplos-tisza-ride.gpx --start-fraction 0.0022 --points 30 --period-s 1.0 --speed-mps 2.5 --noise-m 1.0 --accuracy-m 4.0
```

What to look for:
- around the tight bend near the start, the snapped route position should stay on the incoming leg until the apex
- after the turn, it should switch once onto the outgoing leg and keep progressing
- it should not jump forward onto the return leg early, then jump back
