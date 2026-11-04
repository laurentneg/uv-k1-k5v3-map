"use strict";

import { centiArcsecondsToDegrees } from "../serial/k5viewer.js";

const SOURCE_LABEL = {
  0: "other",
  1: "sonde",
  2: "epirb",
  3: "aprs",
};

export class MapView {
  constructor(elementId) {
    this.map = L.map(elementId, { zoomControl: true }).setView([46.5, 2.5], 6);
    this.tileLayer = L.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png", {
      maxZoom: 19,
      attribution: "&copy; OpenStreetMap",
    }).addTo(this.map);
    this.me = null;
    this.targets = new Map();
    this.history = [];
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
      }).addTo(this.map).bindPopup("Phone GPS");
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
    const html = `<strong>${key}</strong><br>${src}<br>${lat.toFixed(5)}, ${lon.toFixed(5)}` +
      (pkt.altM ? `<br>${pkt.altM} m` : "");

    if (this.targets.has(key)) {
      const m = this.targets.get(key);
      m.setLatLng([lat, lon]).setPopupContent(html);
    } else {
      const m = L.marker([lat, lon]).addTo(this.map).bindPopup(html);
      this.targets.set(key, m);
    }
    this.history.unshift({
      label: key,
      source: src,
      lat,
      lon,
      altM: pkt.altM,
      at: new Date(),
    });
    if (this.history.length > 40) this.history.length = 40;
    return this.history;
  }

  /** Prefetch OSM tiles around a point for offline use (best-effort). */
  async prefetchAround(lat, lon, { radiusKm = 30, zoom = 12 } = {}) {
    const n = Math.ceil(radiusKm / (40075 * Math.cos((lat * Math.PI) / 180) / Math.pow(2, zoom) / 256 * 256 / 1000));
    const x0 = lon2tile(lon, zoom);
    const y0 = lat2tile(lat, zoom);
    const urls = [];
    for (let dx = -n; dx <= n; dx++) {
      for (let dy = -n; dy <= n; dy++) {
        urls.push(`https://tile.openstreetmap.org/${zoom}/${x0 + dx}/${y0 + dy}.png`);
      }
    }
    // Cap to avoid hammering OSM on first use.
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
