"use strict";

import { degreesToCentiArcseconds } from "../serial/k5viewer.js";

/** Watch the phone GPS and emit fixes often enough for the radio's 15 s TTL. */
export class PhoneGps {
  constructor({ onFix, onError, onStatus } = {}) {
    this.onFix = onFix || (() => {});
    this.onError = onError || (() => {});
    this.onStatus = onStatus || (() => {});
    this.watchId = null;
    this.lastFix = null;
    this.injectTimer = null;
  }

  get supported() {
    return "geolocation" in navigator;
  }

  start() {
    if (!this.supported) {
      this.onError(new Error("Geolocation is not available"));
      return;
    }
    if (this.watchId != null) return;
    this.onStatus("requesting");
    this.watchId = navigator.geolocation.watchPosition(
      (pos) => {
        const fix = {
          lat: pos.coords.latitude,
          lon: pos.coords.longitude,
          accuracy: pos.coords.accuracy,
          latCas: degreesToCentiArcseconds(pos.coords.latitude),
          lonCas: degreesToCentiArcseconds(pos.coords.longitude),
          at: Date.now(),
        };
        this.lastFix = fix;
        this.onStatus("fix");
        this.onFix(fix);
      },
      (err) => {
        this.onStatus("error");
        this.onError(err);
      },
      {
        enableHighAccuracy: true,
        maximumAge: 2000,
        timeout: 15000,
      }
    );
    // Re-inject at least every 8 s so the radio's 15 s GPS TTL never expires.
    this.injectTimer = setInterval(() => {
      if (this.lastFix) this.onFix(this.lastFix);
    }, 8000);
  }

  stop() {
    if (this.watchId != null) {
      navigator.geolocation.clearWatch(this.watchId);
      this.watchId = null;
    }
    if (this.injectTimer) {
      clearInterval(this.injectTimer);
      this.injectTimer = null;
    }
    this.onStatus("stopped");
  }
}
