"use strict";

import { centiArcsecondsToDegrees } from "../serial/k5viewer.js";

const SOURCE_LABEL = {
  0: "other",
  1: "sonde",
  2: "epirb",
  3: "aprs",
  4: "ais",
};

const SOURCE_SONDE = 1;
const TRACK_MAX_POINTS = 2000;
const TRACK_MIN_MOVE_M = 11;

const TRACK_COLORS = [
  "#e67e22",
  "#2980b9",
  "#8e44ad",
  "#27ae60",
  "#c0392b",
];

function haversineM(a, b) {
  const R = 6371000;
  const toRad = (d) => (d * Math.PI) / 180;
  const dLat = toRad(b[0] - a[0]);
  const dLon = toRad(b[1] - a[1]);
  const lat1 = toRad(a[0]);
  const lat2 = toRad(b[0]);
  const h =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(h));
}

function balloonIcon() {
  return L.divIcon({
    className: "marker-balloon",
    html: `<svg viewBox="0 0 40 52" width="32" height="42" aria-hidden="true">
      <ellipse cx="20" cy="16" rx="12" ry="14" fill="#e67e22" stroke="#fff" stroke-width="2"/>
      <path d="M14 28 L20 48 L26 28" fill="none" stroke="#e67e22" stroke-width="2"/>
    </svg>`,
    iconSize: [32, 42],
    iconAnchor: [16, 42],
    popupAnchor: [0, -36],
  });
}

function aprsIcon() {
  return L.divIcon({
    className: "marker-aprs",
    html: `<svg viewBox="0 0 40 52" width="28" height="36" aria-hidden="true">
      <path d="M20 4 L36 40 H4 Z" fill="#3498db" stroke="#fff" stroke-width="2"/>
    </svg>`,
    iconSize: [28, 36],
    iconAnchor: [14, 36],
    popupAnchor: [0, -30],
  });
}

function epirbIcon() {
  return L.divIcon({
    className: "marker-epirb",
    html: `<svg viewBox="0 0 40 52" width="28" height="36" aria-hidden="true">
      <circle cx="20" cy="20" r="12" fill="#e74c3c" stroke="#fff" stroke-width="2"/>
      <text x="20" y="25" text-anchor="middle" fill="#fff" font-size="14" font-weight="700">!</text>
    </svg>`,
    iconSize: [28, 36],
    iconAnchor: [14, 36],
    popupAnchor: [0, -30],
  });
}

function iconForSource(source) {
  if (source === 1) return balloonIcon();
  if (source === 2) return epirbIcon();
  return aprsIcon();
}

export class MapView {
  constructor(elementId) {
    this.map = L.map(elementId, { zoomControl: true }).setView([46.5, 2.5], 6);
    this.tileLayer = L.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png", {
      maxZoom: 19,
      attribution: "&copy; OpenStreetMap",
    }).addTo(this.map);
    this.me = null;
    this.meAccuracy = null;
    this.targets = new Map();
    this.tracks = new Map();
    this.trackPoints = new Map();
    this.trackColorIdx = 0;
    this.history = [];
    this.statusLog = [];
  }

  setMe(lat, lon, accuracy) {
    if (this.me) {
      this.me.setLatLng([lat, lon]);
      if (this.meAccuracy) this.meAccuracy.setLatLng([lat, lon]).setRadius(accuracy || 0);
    } else {
      this.me = L.circleMarker([lat, lon], {
        radius: 8,
        color: "#063",
        fillColor: "#63e6be",
        fillOpacity: 0.9,
        weight: 2,
      }).addTo(this.map).bindPopup("GPS téléphone");
      this.meAccuracy = L.circle([lat, lon], {
        radius: accuracy || 0,
        color: "#63e6be",
        fillOpacity: 0.08,
        weight: 1,
      }).addTo(this.map);
      this.map.setView([lat, lon], 13);
    }
  }

  setReported(pkt) {
    const lat = centiArcsecondsToDegrees(pkt.latCas);
    const lon = centiArcsecondsToDegrees(pkt.lonCas);
    const key = pkt.label || "target";
    const src = SOURCE_LABEL[pkt.source] || "other";
    const html =
      `<strong>${key}</strong><br>${src}<br>${lat.toFixed(5)}, ${lon.toFixed(5)}` +
      (pkt.altM ? `<br>${pkt.altM} m` : "");

    if (this.targets.has(key)) {
      const m = this.targets.get(key);
      m.setLatLng([lat, lon]).setPopupContent(html);
      m.setIcon(iconForSource(pkt.source));
    } else {
      const m = L.marker([lat, lon], { icon: iconForSource(pkt.source) })
        .addTo(this.map)
        .bindPopup(html);
      this.targets.set(key, m);
      this.map.setView([lat, lon], Math.max(this.map.getZoom(), 12));
      m.openPopup();
    }

    if (pkt.source === SOURCE_SONDE) {
      this.#appendSondeTrack(key, [lat, lon]);
    }

    this.history.unshift({
      label: key,
      source: src,
      lat,
      lon,
      altM: pkt.altM,
      at: new Date(),
    });
    if (this.history.length > 80) this.history.length = 80;
    return this.history;
  }

  #appendSondeTrack(key, latlng) {
    const pts = this.trackPoints.get(key) || [];
    const last = pts[pts.length - 1];
    if (last && haversineM(last, latlng) < TRACK_MIN_MOVE_M) return;
    pts.push(latlng);
    while (pts.length > TRACK_MAX_POINTS) pts.shift();
    this.trackPoints.set(key, pts);

    let poly = this.tracks.get(key);
    if (!poly) {
      const color = TRACK_COLORS[this.trackColorIdx++ % TRACK_COLORS.length];
      poly = L.polyline(pts, {
        color,
        weight: 4,
        opacity: 0.85,
        lineJoin: "round",
      }).addTo(this.map);
      this.tracks.set(key, poly);
    } else {
      poly.setLatLngs(pts);
    }
  }

  /** Prefetch OSM tiles around a point for offline use (best-effort). */
  async prefetchAround(lat, lon, { radiusKm = 30, zoom = 12 } = {}) {
    const n = Math.ceil(
      radiusKm /
        (40075 * Math.cos((lat * Math.PI) / 180) / Math.pow(2, zoom) / 256 * 256 / 1000)
    );
    const x0 = lon2tile(lon, zoom);
    const y0 = lat2tile(lat, zoom);
    const urls = [];
    for (let dx = -n; dx <= n; dx++) {
      for (let dy = -n; dy <= n; dy++) {
        urls.push(`https://tile.openstreetmap.org/${zoom}/${x0 + dx}/${y0 + dy}.png`);
      }
    }
    const limited = urls.slice(0, 400);
    await Promise.allSettled(limited.map((u) => fetch(u, { mode: "cors" })));
    return limited.length;
  }
}

function lon2tile(lon, zoom) {
  return Math.floor(((lon + 180) / 360) * Math.pow(2, zoom));
}

function lat2tile(lat, zoom) {
  return Math.floor(
    ((1 - Math.log(Math.tan((lat * Math.PI) / 180) + 1 / Math.cos((lat * Math.PI) / 180)) / Math.PI) / 2) *
      Math.pow(2, zoom)
  );
}
