"use strict";

import { K5ViewerLink } from "./serial/k5viewer.js";
import { PhoneGps } from "./gps/phone.js";
import { MapView } from "./map/map.js";

const el = {
  status: document.querySelector("#statusLine"),
  connect: document.querySelector("#connectBtn"),
  disconnect: document.querySelector("#disconnectBtn"),
  gps: document.querySelector("#gpsBtn"),
  prefetch: document.querySelector("#prefetchBtn"),
  list: document.querySelector("#targetList"),
};

const map = new MapView("map");
let gpsRunning = false;
let lastPhoneFix = null;

function setStatus(text) {
  el.status.textContent = text;
}

function renderHistory(history) {
  el.list.innerHTML = "";
  for (const item of history) {
    const li = document.createElement("li");
    const when = item.at.toLocaleTimeString([], { hour12: false });
    li.textContent = `${when} · ${item.label} · ${item.source} · ${item.lat.toFixed(5)}, ${item.lon.toFixed(5)}` +
      (item.altM ? ` · ${item.altM} m` : "");
    el.list.appendChild(li);
  }
}

const link = new K5ViewerLink({
  onStatus(kind, err) {
    if (kind === "connected") {
      setStatus("Radio connectée");
      el.connect.disabled = true;
      el.disconnect.disabled = false;
    } else if (kind === "disconnected") {
      setStatus("Radio déconnectée");
      el.connect.disabled = !link.supported;
      el.disconnect.disabled = true;
    } else if (kind === "error") {
      setStatus(`Erreur série: ${err && err.message ? err.message : err}`);
    }
  },
  onReported(pkt) {
    renderHistory(map.setReported(pkt));
    setStatus(`Cible ${pkt.label || "?"} reçue`);
  },
});

const phone = new PhoneGps({
  onStatus(kind) {
    if (!link.connected) {
      if (kind === "fix") setStatus("GPS téléphone OK");
      else if (kind === "requesting") setStatus("Autorisation GPS…");
      else if (kind === "error") setStatus("GPS téléphone indisponible");
    }
  },
  onError(err) {
    setStatus(`GPS: ${err.message || err}`);
  },
  async onFix(fix) {
    lastPhoneFix = fix;
    map.setMe(fix.lat, fix.lon, fix.accuracy);
    el.prefetch.disabled = false;
    if (link.connected) {
      try {
        await link.sendGps(fix.latCas, fix.lonCas);
      } catch (err) {
        setStatus(`Envoi GPS: ${err.message || err}`);
      }
    }
  },
});

el.connect.disabled = !link.supported;
if (!link.supported) setStatus("Web Serial requis (Chrome / Edge Android ou desktop)");

el.connect.addEventListener("click", async () => {
  try {
    await link.connect();
    if (lastPhoneFix) await link.sendGps(lastPhoneFix.latCas, lastPhoneFix.lonCas);
  } catch (err) {
    setStatus(err.message || String(err));
  }
});

el.disconnect.addEventListener("click", () => link.disconnect());

el.gps.addEventListener("click", () => {
  if (gpsRunning) {
    phone.stop();
    gpsRunning = false;
    el.gps.textContent = "GPS téléphone";
  } else {
    phone.start();
    gpsRunning = true;
    el.gps.textContent = "Arrêter GPS";
  }
});

el.prefetch.addEventListener("click", async () => {
  if (!lastPhoneFix) return;
  el.prefetch.disabled = true;
  setStatus("Téléchargement des tuiles…");
  try {
    const n = await map.prefetchAround(lastPhoneFix.lat, lastPhoneFix.lon);
    setStatus(`${n} tuiles demandées (cache SW)`);
  } catch (err) {
    setStatus(`Tuiles: ${err.message || err}`);
  } finally {
    el.prefetch.disabled = false;
  }
});

if ("serviceWorker" in navigator) {
  navigator.serviceWorker.register("./sw.js").catch(() => {});
}

// Auto-start GPS on load for field use.
phone.start();
gpsRunning = true;
el.gps.textContent = "Arrêter GPS";
