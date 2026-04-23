const SVG_WIDTH = 1000;
const SVG_HEIGHT = 760;
const DEFAULT_TOLERANCE_METERS = 20;
const DEFAULT_VIEW_SCALE = 500;
const DEFAULT_LOCATION_INTERVAL_SECONDS = 5;
const EARTH_RADIUS_METERS = 6371000;

const state = {
  fileName: "",
  rawSegments: null,
  routeModel: null,
  displayModel: null,
  analysis: null,
  displayAnalysis: null,
  latestFix: null,
  locationTimerId: null,
  locationIntervalSeconds: DEFAULT_LOCATION_INTERVAL_SECONDS,
  isReversed: false,
  viewScale: DEFAULT_VIEW_SCALE,
  ui: null,
};

function init() {
  state.ui = {
    fileInput: document.getElementById("gpx-file"),
    fileName: document.getElementById("file-name"),
    tolerance: document.getElementById("simplify-tolerance"),
    toleranceReadout: document.getElementById("tolerance-readout"),
    locationInterval: document.getElementById("location-interval"),
    locationIntervalReadout: document.getElementById("location-interval-readout"),
    locateButton: document.getElementById("locate-button"),
    locateOnceButton: document.getElementById("center-button"),
    reverseButton: document.getElementById("reverse-button"),
    statusTitle: document.getElementById("status-title"),
    statusBody: document.getElementById("status-body"),
    routeBadge: document.getElementById("route-badge"),
    length: document.getElementById("stat-length"),
    points: document.getElementById("stat-points"),
    progress: document.getElementById("stat-progress"),
    offset: document.getElementById("stat-offset"),
    remaining: document.getElementById("stat-remaining"),
    accuracy: document.getElementById("stat-accuracy"),
    progressShell: document.getElementById("progress-shell"),
    progressFill: document.getElementById("progress-fill"),
    progressMarker: document.getElementById("progress-marker"),
    progressCaption: document.getElementById("progress-caption"),
    scaleButtons: Array.from(document.querySelectorAll(".scale-button")),
    scaleCaption: document.getElementById("scale-caption"),
    fullscreenButton: document.getElementById("fullscreen-button"),
    routePanel: document.getElementById("route-panel"),
    routeView: document.getElementById("route-view"),
  };

  state.ui.fileInput.addEventListener("change", handleFileSelection);
  state.ui.tolerance.addEventListener("input", handleToleranceChange);
  state.ui.locationInterval.addEventListener("input", handleLocationIntervalChange);
  state.ui.locationInterval.addEventListener("change", handleLocationIntervalChange);
  state.ui.locateButton.addEventListener("click", toggleAutoUpdate);
  state.ui.locateOnceButton.addEventListener("click", requestSingleFix);
  state.ui.reverseButton.addEventListener("click", toggleRouteDirection);
  state.ui.fullscreenButton.addEventListener("click", toggleFullscreen);
  for (const button of state.ui.scaleButtons) {
    button.addEventListener("click", handleScaleSelection);
  }
  document.addEventListener("fullscreenchange", renderRouteActions);
  document.addEventListener("webkitfullscreenchange", renderRouteActions);

  state.ui.tolerance.value = String(DEFAULT_TOLERANCE_METERS);
  state.ui.locationInterval.value = String(DEFAULT_LOCATION_INTERVAL_SECONDS);
  updateToleranceReadout();
  updateLocationIntervalReadout();
  render();
}

async function handleFileSelection(event) {
  const [file] = event.target.files || [];
  if (!file) {
    return;
  }

  try {
    const text = await file.text();
    const parsedSegments = parseGpxText(text);
    state.fileName = file.name;
    state.rawSegments = parsedSegments;
    state.isReversed = false;
    rebuildRouteModel();
    state.ui.fileName.textContent = file.name;
    render();
  } catch (error) {
    console.error(error);
    state.fileName = "";
    state.rawSegments = null;
    state.routeModel = null;
    state.displayModel = null;
    state.analysis = null;
    state.displayAnalysis = null;
    state.isReversed = false;
    state.ui.fileName.textContent = "Could not read that GPX file.";
    renderStatus("GPX parse failed", error.message, "idle");
    renderStats();
    renderRouteFigure();
  }
}

function handleToleranceChange() {
  updateToleranceReadout();
  if (!state.routeModel) {
    return;
  }
  recomputeDisplayModel();
  render();
}

function updateToleranceReadout() {
  state.ui.toleranceReadout.textContent = `${state.ui.tolerance.value} m`;
}

function handleLocationIntervalChange() {
  state.locationIntervalSeconds =
    state.ui.locationInterval.valueAsNumber ||
    Number(state.ui.locationInterval.value) ||
    DEFAULT_LOCATION_INTERVAL_SECONDS;
  updateLocationIntervalReadout();
  if (state.locationTimerId !== null) {
    restartAutoUpdateTimer();
  }
}

function updateLocationIntervalReadout() {
  state.ui.locationIntervalReadout.textContent = `${state.locationIntervalSeconds} s`;
}

function handleScaleSelection(event) {
  const { scale } = event.currentTarget.dataset;
  state.viewScale = scale === "fit" ? "fit" : Number(scale);
  renderRouteFigure();
}

function toggleRouteDirection() {
  if (!state.rawSegments) {
    return;
  }
  state.isReversed = !state.isReversed;
  rebuildRouteModel();
  render();
}

function toggleAutoUpdate() {
  if (!navigator.geolocation) {
    renderStatus(
      "Geolocation unavailable",
      "This browser does not expose geolocation. Use a browser on the phone.",
      "away"
    );
    return;
  }

  if (state.locationTimerId !== null) {
    stopAutoUpdateTimer();
    render();
    return;
  }

  requestSingleFix();
  restartAutoUpdateTimer();
  render();
}

function requestSingleFix() {
  if (!navigator.geolocation) {
    renderStatus(
      "Geolocation unavailable",
      "This browser does not expose geolocation. Use a browser on the phone.",
      "away"
    );
    return;
  }

  navigator.geolocation.getCurrentPosition(
    handleLocationSuccess,
    handleLocationError,
    {
      enableHighAccuracy: true,
      maximumAge: 0,
      timeout: 15000,
    }
  );
}

function handleLocationSuccess(position) {
  state.latestFix = {
    lat: position.coords.latitude,
    lon: position.coords.longitude,
    accuracy: position.coords.accuracy || null,
    heading: position.coords.heading,
    speed: position.coords.speed,
    timestamp: position.timestamp,
  };
  recomputeAnalysis();
  render();
}

function handleLocationError(error) {
  renderStatus(
    "Location failed",
    `${error.message}. If you are testing from the phone browser, use localhost and allow location access.`,
    "away"
  );
}

function restartAutoUpdateTimer() {
  stopAutoUpdateTimer();
  state.locationTimerId = window.setInterval(
    requestSingleFix,
    state.locationIntervalSeconds * 1000
  );
}

function stopAutoUpdateTimer() {
  if (state.locationTimerId !== null) {
    window.clearInterval(state.locationTimerId);
    state.locationTimerId = null;
  }
}

function recomputeDisplayModel() {
  if (!state.routeModel) {
    state.displayModel = null;
    return;
  }

  const toleranceMeters = Number(state.ui.tolerance.value) || 0;
  state.displayModel = buildDisplayModel(state.routeModel, toleranceMeters);
  recomputeAnalysis();
}

function rebuildRouteModel() {
  if (!state.rawSegments) {
    state.routeModel = null;
    state.displayModel = null;
    state.analysis = null;
    state.displayAnalysis = null;
    return;
  }

  state.routeModel = buildRouteModel(getDirectedSegments(state.rawSegments, state.isReversed));
  recomputeDisplayModel();
}

function recomputeAnalysis() {
  state.analysis = null;
  state.displayAnalysis = null;

  if (!state.routeModel || !state.latestFix) {
    return;
  }

  state.analysis = analyzeLocationAgainstModel(state.routeModel, state.latestFix);
  if (state.displayModel) {
    state.displayAnalysis = analyzeProjectedPoint(
      state.displayModel,
      projectPoint(state.latestFix, state.displayModel.projection)
    );
  }
}

function render() {
  renderSummary();
  renderStats();
  renderProgress();
  renderLocationControls();
  renderRouteActions();
  renderScaleControls();
  renderRouteFigure();
}

function renderSummary() {
  if (!state.routeModel) {
    renderStatus(
      "Waiting for a GPX file",
      "Load a route first. Then start location tracking.",
      "idle"
    );
    return;
  }

  if (!state.latestFix) {
    renderStatus(
      "Route loaded",
      "The route is ready. Start locating to project your current position onto it.",
      "idle"
    );
    return;
  }

  const analysis = state.analysis;
  const threshold = Math.max(15, analysis.accuracy || 0);
  const badgeKind = analysis.offRouteMeters <= threshold ? "on" : "away";
  const title = badgeKind === "on" ? "You are close to the route" : "You are away from the route";
  const body = [
    `${formatDistance(analysis.progressMeters)} from the start,`,
    `${formatDistance(analysis.remainingMeters)} remaining,`,
    `${formatDistance(analysis.offRouteMeters)} lateral offset.`,
  ].join(" ");

  renderStatus(title, body, badgeKind);
}

function renderStatus(title, body, badgeKind) {
  state.ui.statusTitle.textContent = title;
  state.ui.statusBody.textContent = body;

  state.ui.routeBadge.className = `route-badge route-badge--${badgeKind}`;
  state.ui.routeBadge.textContent =
    badgeKind === "on" ? "On route" :
    badgeKind === "away" ? "Off route" :
    "Idle";
}

function renderStats() {
  const routeModel = state.routeModel;
  const displayModel = state.displayModel;
  const analysis = state.analysis;

  state.ui.length.textContent = routeModel ? formatDistance(routeModel.totalLengthMeters) : "-";

  if (routeModel && displayModel) {
    state.ui.points.textContent = `${displayModel.pointCount}/${routeModel.pointCount}`;
  } else {
    state.ui.points.textContent = "-";
  }

  state.ui.progress.textContent = analysis ? formatDistance(analysis.progressMeters) : "-";
  state.ui.offset.textContent = analysis ? formatDistance(analysis.offRouteMeters) : "-";
  state.ui.remaining.textContent = analysis ? formatDistance(analysis.remainingMeters) : "-";
  state.ui.accuracy.textContent = analysis && analysis.accuracy ? `±${formatDistance(analysis.accuracy)}` : "-";
}

function renderProgress() {
  if (!state.analysis || !state.routeModel || state.routeModel.totalLengthMeters <= 0) {
    state.ui.progressShell.classList.add("progress-shell--hidden");
    state.ui.progressShell.setAttribute("aria-hidden", "true");
    return;
  }

  const ratio = clamp(state.analysis.progressRatio, 0, 1);
  state.ui.progressShell.classList.remove("progress-shell--hidden");
  state.ui.progressShell.setAttribute("aria-hidden", "false");
  state.ui.progressFill.style.width = `${ratio * 100}%`;
  state.ui.progressMarker.style.left = `${ratio * 100}%`;
  state.ui.progressCaption.textContent = `${Math.round(ratio * 100)}% of route`;
}

function renderRouteFigure() {
  const svg = state.ui.routeView;

  if (!state.displayModel) {
    svg.innerHTML = buildPlaceholderSvg("Load a GPX route to draw it here.");
    return;
  }

  const viewWindow = buildViewWindow();
  const screen = createScreenProjector(viewWindow.bounds);

  const polylines = viewWindow.segments
    .map((segment) => {
      const points = segment.map((point) => toScreenPoint(point, screen)).join(" ");
      return `<polyline class="route-line" points="${points}" />`;
    })
    .join("");

  const visibleStartPoint = isPointWithinBounds(state.displayModel.segments[0]?.points[0], viewWindow.bounds)
    ? toScreenObject(state.displayModel.segments[0].points[0], screen)
    : null;
  const lastSegment = [...state.displayModel.segments].reverse().find((segment) => segment.points.length);
  const lastPoint = lastSegment ? lastSegment.points[lastSegment.points.length - 1] : null;
  const visibleEndPoint = isPointWithinBounds(lastPoint, viewWindow.bounds)
    ? toScreenObject(lastPoint, screen)
    : null;

  let overlays = "";
  if (visibleStartPoint) {
    overlays += `
      <circle class="marker marker--start" cx="${visibleStartPoint.x}" cy="${visibleStartPoint.y}" r="10" />
      <text class="marker-label" x="${visibleStartPoint.x + 12}" y="${visibleStartPoint.y - 12}">Start</text>
    `;
  }
  if (visibleEndPoint) {
    overlays += `
      <circle class="marker marker--finish" cx="${visibleEndPoint.x}" cy="${visibleEndPoint.y}" r="10" />
      <text class="marker-label" x="${visibleEndPoint.x + 12}" y="${visibleEndPoint.y - 12}">Finish</text>
    `;
  }

  if (state.displayAnalysis?.nearestPoint && state.displayAnalysis?.point) {
    const nearestVisible = isPointWithinBounds(state.displayAnalysis.nearestPoint, viewWindow.bounds);
    const userVisible = isPointWithinBounds(state.displayAnalysis.point, viewWindow.bounds);

    if (nearestVisible) {
      const nearest = toScreenObject(state.displayAnalysis.nearestPoint, screen);
      overlays += `
        <circle class="marker marker--nearest" cx="${nearest.x}" cy="${nearest.y}" r="7" />
      `;

      if (userVisible) {
        const user = toScreenObject(state.displayAnalysis.point, screen);
        overlays += `
          <line class="connector" x1="${user.x}" y1="${user.y}" x2="${nearest.x}" y2="${nearest.y}" />
          <circle class="marker marker--user" cx="${user.x}" cy="${user.y}" r="11" />
          <text class="marker-label marker-label--user" x="${user.x + 14}" y="${user.y - 14}">You</text>
        `;
      } else {
        const clippedOffset = clipSegmentToBounds(
          state.displayAnalysis.nearestPoint,
          state.displayAnalysis.point,
          viewWindow.bounds
        );
        if (clippedOffset) {
          const edge = toScreenObject(clippedOffset.end, screen);
          const label = computeOffsetLabelPosition(nearest, edge);
          overlays += `
            <line class="connector connector--offset" x1="${nearest.x}" y1="${nearest.y}" x2="${edge.x}" y2="${edge.y}" />
            <circle class="marker marker--edge" cx="${edge.x}" cy="${edge.y}" r="9" />
            <text
              class="marker-label marker-label--edge"
              x="${label.x}"
              y="${label.y}"
              text-anchor="${label.anchor}"
            >${escapeXml(`${formatDistance(state.analysis.offRouteMeters)} off route`)}</text>
          `;
        }
      }
    }
  }

  const scaleTag = viewWindow.scaleLabel ? `
    <rect class="window-frame" x="18" y="18" width="${SVG_WIDTH - 36}" height="${SVG_HEIGHT - 36}" rx="20" />
    <text class="scale-tag" x="54" y="64">${escapeXml(viewWindow.scaleLabel)}</text>
  ` : "";

  svg.innerHTML = `
    <defs>
      <filter id="route-shadow" x="-20%" y="-20%" width="140%" height="140%">
        <feDropShadow dx="0" dy="8" stdDeviation="10" flood-color="rgba(24, 32, 40, 0.16)" />
      </filter>
    </defs>
    <rect x="0" y="0" width="${SVG_WIDTH}" height="${SVG_HEIGHT}" fill="rgba(255, 255, 255, 0.08)" />
    ${scaleTag}
    <g filter="url(#route-shadow)">
      ${polylines}
      ${overlays}
    </g>
  `;
}

function renderScaleControls() {
  const currentScale = state.viewScale;
  const hasFix = Boolean(state.displayAnalysis?.nearestPoint && state.displayAnalysis?.point);
  const effectiveScale = hasFix ? currentScale : "fit";

  for (const button of state.ui.scaleButtons) {
    const { scale } = button.dataset;
    const value = scale === "fit" ? "fit" : Number(scale);
    button.classList.toggle("is-active", value === effectiveScale);
    button.disabled = scale !== "fit" && !hasFix;
  }

  if (!state.displayModel) {
    state.ui.scaleCaption.textContent = "Scale: load a route";
    return;
  }

  if (currentScale === "fit" || !hasFix) {
    state.ui.scaleCaption.textContent = hasFix
      ? "Scale: full route"
      : "Scale: full route until a location fix is available";
    return;
  }

  state.ui.scaleCaption.textContent = `Scale: ${formatDistance(currentScale)} around the nearest route point`;
}

function renderRouteActions() {
  const fullscreenElement = document.fullscreenElement || document.webkitFullscreenElement;

  state.ui.reverseButton.disabled = !state.rawSegments;
  state.ui.reverseButton.classList.toggle("is-active", state.isReversed);
  state.ui.reverseButton.setAttribute("aria-pressed", state.isReversed ? "true" : "false");
  state.ui.reverseButton.textContent = state.isReversed ? "Route reversed" : "Reverse route";

  const fullscreenActive = fullscreenElement === state.ui.routePanel;
  state.ui.fullscreenButton.classList.toggle("is-active", fullscreenActive);
  state.ui.fullscreenButton.textContent = fullscreenActive ? "Exit full screen" : "Full screen";
  state.ui.fullscreenButton.setAttribute("aria-pressed", fullscreenActive ? "true" : "false");
}

function buildViewWindow() {
  if (!state.displayModel) {
    return null;
  }

  const allRoutePoints = state.displayModel.segments.flatMap((segment) => segment.points);
  if (state.viewScale === "fit" || !state.displayAnalysis?.nearestPoint || !state.displayAnalysis?.point) {
    return {
      bounds: computeBounds(allRoutePoints),
      segments: state.displayModel.segments.map((segment) => segment.points),
      scaleLabel: "Full route",
    };
  }

  const bounds = createLocalBounds(
    state.displayAnalysis.nearestPoint,
    Number(state.viewScale)
  );
  const segments = clipModelSegmentsToBounds(state.displayModel, bounds);

  return {
    bounds,
    segments: segments.length ? segments : state.displayModel.segments.map((segment) => segment.points),
    scaleLabel: `${formatDistance(Number(state.viewScale))} window`,
  };
}

function buildPlaceholderSvg(message) {
  return `
    <rect x="0" y="0" width="${SVG_WIDTH}" height="${SVG_HEIGHT}" fill="rgba(255,255,255,0.08)" />
    <path d="M140 610 C280 420, 390 430, 530 300 S770 210, 860 150" class="route-line route-line--ghost" />
    <text x="70" y="110" class="placeholder-title">GeePee</text>
    <text x="70" y="152" class="placeholder-copy">${escapeXml(message)}</text>
  `;
}

function renderLocationControls() {
  const autoUpdateActive = state.locationTimerId !== null;
  state.ui.locateButton.textContent = autoUpdateActive ? "Stop auto-update" : "Start auto-update";
  state.ui.locateButton.setAttribute("aria-pressed", autoUpdateActive ? "true" : "false");
}

async function toggleFullscreen() {
  const fullscreenElement = document.fullscreenElement || document.webkitFullscreenElement;

  if (!fullscreenElement) {
    const request = state.ui.routePanel.requestFullscreen || state.ui.routePanel.webkitRequestFullscreen;
    if (request) {
      await request.call(state.ui.routePanel);
    }
    return;
  }

  if (fullscreenElement === state.ui.routePanel) {
    const exit = document.exitFullscreen || document.webkitExitFullscreen;
    if (exit) {
      await exit.call(document);
    }
  }
}

function parseGpxText(text) {
  const parser = new DOMParser();
  const xml = parser.parseFromString(text, "application/xml");
  const parserError = xml.getElementsByTagName("parsererror")[0];
  if (parserError) {
    throw new Error("The GPX file is not valid XML.");
  }

  const trackSegments = Array.from(xml.getElementsByTagName("trkseg"))
    .map((segment) => Array.from(segment.getElementsByTagName("trkpt")).map(readPointNode))
    .filter((segment) => segment.length >= 2);

  if (trackSegments.length > 0) {
    return trackSegments;
  }

  const routePoints = Array.from(xml.getElementsByTagName("rtept")).map(readPointNode);
  if (routePoints.length >= 2) {
    return [routePoints];
  }

  const waypointPoints = Array.from(xml.getElementsByTagName("wpt")).map(readPointNode);
  if (waypointPoints.length >= 2) {
    return [waypointPoints];
  }

  throw new Error("No track or route points were found in the GPX file.");
}

function getDirectedSegments(rawSegments, isReversed) {
  if (!isReversed) {
    return rawSegments.map((segment) => segment.map((point) => ({ ...point })));
  }

  return rawSegments
    .slice()
    .reverse()
    .map((segment) =>
      segment
        .slice()
        .reverse()
        .map((point) => ({ ...point }))
    );
}

function readPointNode(node) {
  const lat = Number(node.getAttribute("lat"));
  const lon = Number(node.getAttribute("lon"));
  if (!Number.isFinite(lat) || !Number.isFinite(lon)) {
    throw new Error("Encountered a GPX point without valid latitude and longitude.");
  }
  return { lat, lon };
}

function buildRouteModel(rawSegments) {
  const allPoints = rawSegments.flat();
  const projection = buildProjection(allPoints);
  let totalLengthMeters = 0;
  let pointCount = 0;

  const segments = rawSegments.map((segment) => {
    const points = segment.map((point) => projectPoint(point, projection));
    pointCount += points.length;
    const cumulativeMeters = [0];
    let lengthMeters = 0;

    for (let index = 1; index < points.length; index += 1) {
      lengthMeters += distanceBetweenProjected(points[index - 1], points[index]);
      cumulativeMeters.push(lengthMeters);
    }

    const offsetMeters = totalLengthMeters;
    totalLengthMeters += lengthMeters;

    return {
      points,
      cumulativeMeters,
      lengthMeters,
      offsetMeters,
    };
  });

  return {
    projection,
    segments,
    pointCount,
    totalLengthMeters,
  };
}

function buildDisplayModel(routeModel, toleranceMeters) {
  let pointCount = 0;
  let totalLengthMeters = 0;

  const segments = routeModel.segments.map((segment) => {
    const kept = simplifyProjectedPoints(segment.points, toleranceMeters);
    pointCount += kept.length;
    const cumulativeMeters = [0];
    let lengthMeters = 0;

    for (let index = 1; index < kept.length; index += 1) {
      lengthMeters += distanceBetweenProjected(kept[index - 1], kept[index]);
      cumulativeMeters.push(lengthMeters);
    }

    const offsetMeters = totalLengthMeters;
    totalLengthMeters += lengthMeters;

    return {
      points: kept,
      cumulativeMeters,
      lengthMeters,
      offsetMeters,
    };
  });

  return {
    projection: routeModel.projection,
    segments,
    pointCount,
    totalLengthMeters,
  };
}

function analyzeLocationAgainstModel(model, fix) {
  const projectedFix = projectPoint(fix, model.projection);
  const nearest = analyzeProjectedPoint(model, projectedFix);

  return {
    ...nearest,
    accuracy: fix.accuracy || null,
    progressMeters: nearest.routeMeters,
    remainingMeters: Math.max(0, model.totalLengthMeters - nearest.routeMeters),
    progressRatio: model.totalLengthMeters > 0 ? nearest.routeMeters / model.totalLengthMeters : 0,
  };
}

function analyzeProjectedPoint(model, projectedFix) {
  let best = null;

  for (const segment of model.segments) {
    if (segment.points.length === 1) {
      const onlyPoint = segment.points[0];
      const distance = distanceBetweenProjected(projectedFix, onlyPoint);
      const candidate = {
        point: projectedFix,
        nearestPoint: onlyPoint,
        offRouteMeters: distance,
        routeMeters: segment.offsetMeters,
      };
      if (!best || candidate.offRouteMeters < best.offRouteMeters) {
        best = candidate;
      }
      continue;
    }

    for (let index = 0; index < segment.points.length - 1; index += 1) {
      const start = segment.points[index];
      const end = segment.points[index + 1];
      const nearest = nearestPointOnSegment(projectedFix, start, end);
      const legLength = distanceBetweenProjected(start, end);
      const routeMeters = segment.offsetMeters + segment.cumulativeMeters[index] + legLength * nearest.t;

      const candidate = {
        point: projectedFix,
        nearestPoint: nearest.point,
        offRouteMeters: nearest.distance,
        routeMeters,
      };

      if (!best || candidate.offRouteMeters < best.offRouteMeters) {
        best = candidate;
      }
    }
  }

  return best || {
    point: projectedFix,
    nearestPoint: projectedFix,
    offRouteMeters: 0,
    routeMeters: 0,
  };
}

function simplifyProjectedPoints(points, toleranceMeters) {
  if (points.length <= 2 || toleranceMeters <= 0) {
    return points.slice();
  }

  const keep = new Set([0, points.length - 1]);

  function walk(startIndex, endIndex) {
    if (endIndex <= startIndex + 1) {
      return;
    }

    let farthestIndex = -1;
    let farthestDistance = toleranceMeters;
    for (let index = startIndex + 1; index < endIndex; index += 1) {
      const distance = perpendicularDistance(points[index], points[startIndex], points[endIndex]);
      if (distance > farthestDistance) {
        farthestDistance = distance;
        farthestIndex = index;
      }
    }

    if (farthestIndex !== -1) {
      keep.add(farthestIndex);
      walk(startIndex, farthestIndex);
      walk(farthestIndex, endIndex);
    }
  }

  walk(0, points.length - 1);
  return Array.from(keep)
    .sort((left, right) => left - right)
    .map((index) => points[index]);
}

function buildProjection(points) {
  const latitudes = points.map((point) => point.lat);
  const longitudes = points.map((point) => point.lon);
  const originLat = (Math.min(...latitudes) + Math.max(...latitudes)) / 2;
  const originLon = (Math.min(...longitudes) + Math.max(...longitudes)) / 2;
  const cosLat = Math.cos((originLat * Math.PI) / 180);

  return { originLat, originLon, cosLat };
}

function projectPoint(point, projection) {
  return {
    ...point,
    x:
      ((point.lon - projection.originLon) * Math.PI / 180) *
      EARTH_RADIUS_METERS *
      projection.cosLat,
    y:
      ((point.lat - projection.originLat) * Math.PI / 180) *
      EARTH_RADIUS_METERS,
  };
}

function nearestPointOnSegment(point, start, end) {
  const dx = end.x - start.x;
  const dy = end.y - start.y;
  const lengthSquared = dx * dx + dy * dy;
  const t = lengthSquared === 0
    ? 0
    : clamp(((point.x - start.x) * dx + (point.y - start.y) * dy) / lengthSquared, 0, 1);

  const projected = {
    x: start.x + dx * t,
    y: start.y + dy * t,
  };

  return {
    t,
    point: projected,
    distance: Math.hypot(point.x - projected.x, point.y - projected.y),
  };
}

function perpendicularDistance(point, start, end) {
  return nearestPointOnSegment(point, start, end).distance;
}

function distanceBetweenProjected(left, right) {
  return Math.hypot(right.x - left.x, right.y - left.y);
}

function computeBounds(points, extras = []) {
  const all = points.concat(extras).filter(Boolean);
  const xs = all.map((point) => point.x);
  const ys = all.map((point) => point.y);
  const minX = Math.min(...xs);
  const maxX = Math.max(...xs);
  const minY = Math.min(...ys);
  const maxY = Math.max(...ys);
  const spanX = maxX - minX || 1;
  const spanY = maxY - minY || 1;
  const padding = Math.max(spanX, spanY) * 0.12 + 12;

  return {
    minX: minX - padding,
    maxX: maxX + padding,
    minY: minY - padding,
    maxY: maxY + padding,
  };
}

function createLocalBounds(anchorPoint, widthMeters) {
  const heightMeters = widthMeters * (SVG_HEIGHT / SVG_WIDTH);
  const halfWidth = widthMeters / 2;
  const halfHeight = heightMeters / 2;

  return {
    minX: anchorPoint.x - halfWidth,
    maxX: anchorPoint.x + halfWidth,
    minY: anchorPoint.y - halfHeight,
    maxY: anchorPoint.y + halfHeight,
  };
}

function clipModelSegmentsToBounds(model, bounds) {
  const output = [];
  for (const segment of model.segments) {
    output.push(...clipPolylineToBounds(segment.points, bounds));
  }
  return output;
}

function clipPolylineToBounds(points, bounds) {
  if (!points.length) {
    return [];
  }

  const polylines = [];
  let current = [];

  for (let index = 0; index < points.length - 1; index += 1) {
    const clipped = clipSegmentToBounds(points[index], points[index + 1], bounds);
    if (!clipped) {
      if (current.length >= 2) {
        polylines.push(current);
      }
      current = [];
      continue;
    }

    if (!current.length) {
      current.push(clipped.start);
    } else if (!sameProjectedPoint(current[current.length - 1], clipped.start)) {
      current.push(clipped.start);
    }
    current.push(clipped.end);
  }

  if (current.length >= 2) {
    polylines.push(current);
  }

  return polylines;
}

function clipSegmentToBounds(start, end, bounds) {
  let t0 = 0;
  let t1 = 1;
  const dx = end.x - start.x;
  const dy = end.y - start.y;
  const checks = [
    [-dx, start.x - bounds.minX],
    [dx, bounds.maxX - start.x],
    [-dy, start.y - bounds.minY],
    [dy, bounds.maxY - start.y],
  ];

  for (const [p, q] of checks) {
    if (p === 0 && q < 0) {
      return null;
    }
    if (p === 0) {
      continue;
    }

    const ratio = q / p;
    if (p < 0) {
      if (ratio > t1) {
        return null;
      }
      if (ratio > t0) {
        t0 = ratio;
      }
    } else {
      if (ratio < t0) {
        return null;
      }
      if (ratio < t1) {
        t1 = ratio;
      }
    }
  }

  return {
    start: interpolateProjectedPoint(start, end, t0),
    end: interpolateProjectedPoint(start, end, t1),
  };
}

function interpolateProjectedPoint(start, end, t) {
  return {
    x: start.x + (end.x - start.x) * t,
    y: start.y + (end.y - start.y) * t,
  };
}

function isPointWithinBounds(point, bounds) {
  return Boolean(
    point &&
    point.x >= bounds.minX &&
    point.x <= bounds.maxX &&
    point.y >= bounds.minY &&
    point.y <= bounds.maxY
  );
}

function sameProjectedPoint(left, right) {
  return Boolean(left && right) &&
    Math.abs(left.x - right.x) < 0.001 &&
    Math.abs(left.y - right.y) < 0.001;
}

function computeOffsetLabelPosition(fromScreen, edgeScreen) {
  const edgeX = Number(edgeScreen.x);
  const edgeY = Number(edgeScreen.y);
  const fromX = Number(fromScreen.x);
  const fromY = Number(fromScreen.y);
  const dx = edgeX - fromX;
  const dy = edgeY - fromY;
  const length = Math.hypot(dx, dy) || 1;
  const unitX = dx / length;
  const unitY = dy / length;
  const pullBack = 24;
  const sideways = edgeX > SVG_WIDTH * 0.72 ? -16 : 16;
  const vertical = edgeY < 72 ? 24 : edgeY > SVG_HEIGHT - 72 ? -14 : 0;

  return {
    x: (edgeX - unitX * pullBack + sideways).toFixed(1),
    y: (edgeY - unitY * pullBack + vertical).toFixed(1),
    anchor: edgeX > SVG_WIDTH * 0.72 ? "end" : "start",
  };
}

function createScreenProjector(bounds) {
  const width = bounds.maxX - bounds.minX || 1;
  const height = bounds.maxY - bounds.minY || 1;
  const scale = Math.min(SVG_WIDTH / width, SVG_HEIGHT / height);
  const usedWidth = width * scale;
  const usedHeight = height * scale;
  const offsetX = (SVG_WIDTH - usedWidth) / 2;
  const offsetY = (SVG_HEIGHT - usedHeight) / 2;

  return {
    scale: scale || 1,
    minX: bounds.minX,
    minY: bounds.minY,
    offsetX,
    offsetY,
  };
}

function toScreenPoint(point, projector) {
  const { x, y } = toScreenObject(point, projector);
  return `${x},${y}`;
}

function toScreenObject(point, projector) {
  return {
    x: (projector.offsetX + (point.x - projector.minX) * projector.scale).toFixed(1),
    y: (SVG_HEIGHT - projector.offsetY - (point.y - projector.minY) * projector.scale).toFixed(1),
  };
}

function formatDistance(meters) {
  if (!Number.isFinite(meters)) {
    return "-";
  }
  if (meters >= 1000) {
    return `${(meters / 1000).toFixed(meters >= 10000 ? 1 : 2)} km`;
  }
  return `${Math.round(meters)} m`;
}

function clamp(value, min, max) {
  return Math.min(Math.max(value, min), max);
}

function escapeXml(text) {
  return String(text)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&apos;");
}

if (typeof window !== "undefined") {
  window.GeePee = {
    parseGpxText,
    getDirectedSegments,
    buildRouteModel,
    buildDisplayModel,
    analyzeLocationAgainstModel,
    clipPolylineToBounds,
    createLocalBounds,
  };
}

if (typeof document !== "undefined") {
  init();
}
