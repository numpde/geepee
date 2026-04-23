# GeePee

Minimal GPX route viewer for the phone browser.

What it does:

- reads a GPX file from local storage
- simplifies the route with an adjustable Douglas-Peucker tolerance
- draws the route as a plain SVG, with no basemap
- projects your current location onto the route
- lets you switch between fixed local scales around the nearest route point
- includes fullscreen route mode and a configurable auto-update timer
- shows along-route progress, remaining distance, and lateral offset

## Run it

From the repo:

```bash
python3 -m http.server 8000
```

On the phone, forward the port through `adb` and open the app as `localhost`:

```bash
adb reverse tcp:8000 tcp:8000
```

Then on the phone browser:

```text
http://127.0.0.1:8000
```

That matters because browser geolocation is much more reliable on a trustworthy origin like `localhost`.

## Notes

- `Tracks` are preferred. If the GPX has no track, `routes` are used. If neither exists, it falls back to waypoints.
- The route shape is simplified for display, but the location math still follows the full route geometry.
- This is a static app. No build step, no framework, no map tiles.
