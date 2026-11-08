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
  linkMode: document.querySelector("#linkMode"),
  logsBtn: document.querySelector("#logsBtn"),
  logsDialog: document.querySelector("#logsDialog"),
  clearLogs: document.querySelector("#clearLogsBtn"),
  list: document.querySelector("#targetList"),
  statusLog: document.querySelector("#statusLog"),
};

const map = new MapView("map");
let gpsRunning = false;
let lastPhoneFix = null;
let lastLoggedStatus = null;
const statusHistory = [];

function setStatus(text, { log = true } = {}) {
  el.status.textContent = text;
  const spam = text.startsWith("GPS →") || text.startsWith("GPS OK");
  if (log && !spam && text !== lastLoggedStatus) {
    lastLoggedStatus = text;
    const when = new Date().toLocaleTimeString([], { hour12: false });
    statusHistory.unshift(`${when}  ${text}`);
    if (statusHistory.length > 200) statusHistory.length = 200;
    if (el.logsDialog.open) renderLogs();
  }
}

function renderHistory(history) {
  el.list.innerHTML = "";
  if (!history.length) {
    const li = document.createElement("li");
    li.textContent = "Aucune cible reçue";
    el.list.appendChild(li);
    return;
  }
  for (const item of history) {
    const li = document.createElement("li");
    const when = item.at.toLocaleTimeString([], { hour12: false });
    li.textContent =
      `${when} · ${item.label} · ${item.source} · ${item.lat.toFixed(5)}, ${item.lon.toFixed(5)}` +
      (item.altM ? ` · ${item.altM} m` : "");
    el.list.appendChild(li);
  }
}

function renderLogs() {
  renderHistory(map.history);
  el.statusLog.textContent = statusHistory.join("\n") || "Aucun message";
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
        setStatus(
          `GPS → radio (${fix.lat.toFixed(5)}, ${fix.lon.toFixed(5)})`,
          { log: false }
        );
      } catch (err) {
        setStatus(`Envoi GPS: ${err.message || err}`);
      }
    }
  },
});

function loadLinkMode() {
  return localStorage.getItem("uvk1_link_mode") || "auto";
}

function saveLinkMode(mode) {
  localStorage.setItem("uvk1_link_mode", mode);
}

el.linkMode.value = loadLinkMode();
link.linkMode = el.linkMode.value;
el.linkMode.addEventListener("change", () => {
  link.linkMode = el.linkMode.value;
  saveLinkMode(el.linkMode.value);
  setStatus(`Lien: ${el.linkMode.selectedOptions[0].textContent}`);
});

el.connect.disabled = !link.supported;
if (!link.supported) setStatus("Web Serial requis (Chrome / Edge)");

el.connect.addEventListener("click", async () => {
  try {
    link.linkMode = el.linkMode.value;
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
    el.gps.textContent = "GPS";
  } else {
    phone.start();
    gpsRunning = true;
    el.gps.textContent = "Stop GPS";
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

el.logsBtn.addEventListener("click", () => {
  renderLogs();
  el.logsDialog.showModal();
});

el.clearLogs.addEventListener("click", () => {
  statusHistory.length = 0;
  map.history.length = 0;
  renderLogs();
});

if ("serviceWorker" in navigator) {
  navigator.serviceWorker.register("./sw.js").catch(() => {});
}

phone.start();
gpsRunning = true;
el.gps.textContent = "Stop GPS";
