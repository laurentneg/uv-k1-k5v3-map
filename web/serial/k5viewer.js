"use strict";

/** K5Viewer Web Serial protocol for UV-K1 Fusion firmware. */
export const BAUD = 38400;
export const GPS_TYPE = 0x07;
export const REPORTED_POS_TYPE = 0x08;
export const KEEP_ALIVE = new Uint8Array([0x55, 0xaa, 0x00, 0x00]);

export function degreesToCentiArcseconds(degrees) {
  return Math.round(degrees * 360000);
}

export function centiArcsecondsToDegrees(cas) {
  return cas / 360000;
}

function readI32LE(bytes, offset) {
  const v =
    bytes[offset] |
    (bytes[offset + 1] << 8) |
    (bytes[offset + 2] << 16) |
    (bytes[offset + 3] << 24);
  return v | 0;
}

function writeI32LE(bytes, offset, value) {
  bytes[offset] = value & 0xff;
  bytes[offset + 1] = (value >> 8) & 0xff;
  bytes[offset + 2] = (value >> 16) & 0xff;
  bytes[offset + 3] = (value >> 24) & 0xff;
}

/** Build GPS inject packet AA 55 07 lat lon XOR. */
export function makeGpsPacket(latCas, lonCas) {
  const packet = new Uint8Array(12);
  packet[0] = 0xaa;
  packet[1] = 0x55;
  packet[2] = GPS_TYPE;
  writeI32LE(packet, 3, latCas);
  writeI32LE(packet, 7, lonCas);
  let xor = 0;
  for (let i = 2; i < 11; i++) xor ^= packet[i];
  packet[11] = xor;
  return packet;
}

function labelFromBytes(bytes, offset) {
  let out = "";
  for (let i = 0; i < 12; i++) {
    const c = bytes[offset + i];
    if (c === 0) break;
    if (c >= 32 && c < 127) out += String.fromCharCode(c);
  }
  return out.trim() || "target";
}

/**
 * Incremental parser for AA 55 framed packets from the radio.
 * Emits { type: "reported", label, latCas, lonCas, altM, source }.
 */
export class K5ViewerParser {
  constructor(onPacket) {
    this.onPacket = onPacket;
    this.buf = new Uint8Array(0);
  }

  push(chunk) {
    const joined = new Uint8Array(this.buf.length + chunk.length);
    joined.set(this.buf);
    joined.set(chunk, this.buf.length);
    this.buf = joined;
    this.#drain();
  }

  #drain() {
    while (this.buf.length >= 3) {
      // Skip optional 0xF0 state markers.
      if ((this.buf[0] & 0xf0) === 0xf0) {
        this.buf = this.buf.subarray(1);
        continue;
      }
      if (this.buf[0] !== 0xaa) {
        this.buf = this.buf.subarray(1);
        continue;
      }
      if (this.buf.length < 2) return;
      if (this.buf[1] !== 0x55) {
        this.buf = this.buf.subarray(1);
        continue;
      }
      if (this.buf.length < 3) return;
      const type = this.buf[2];

      if (type === REPORTED_POS_TYPE) {
        if (this.buf.length < 29) return;
        const frame = this.buf.subarray(0, 29);
        let xor = 0;
        for (let i = 2; i < 28; i++) xor ^= frame[i];
        if (xor === frame[28]) {
          this.onPacket({
            type: "reported",
            label: labelFromBytes(frame, 3),
            latCas: readI32LE(frame, 15),
            lonCas: readI32LE(frame, 19),
            altM: readI32LE(frame, 23),
            source: frame[27],
          });
        }
        this.buf = this.buf.subarray(29);
        continue;
      }

      if (type === 0x02 || type === 0x05 || type === 0x06) {
        if (this.buf.length < 5) return;
        const len = (this.buf[3] << 8) | this.buf[4];
        const total = 5 + len + 1; // header + payload + 0x0A
        if (this.buf.length < total) return;
        this.buf = this.buf.subarray(total);
        continue;
      }

      // Unknown AA 55 type: skip the type byte pair start and resync.
      this.buf = this.buf.subarray(1);
    }
  }
}

/** USB vendor filters for Web Serial requestPort(). */
export const LINK_FILTERS = {
  auto: [
    { usbVendorId: 0x36b7 }, // Fusion / UV-K1 CDC
    { usbVendorId: 0x1a86 }, // CH340 / CH9102
    { usbVendorId: 0x10c4 }, // CP210x
    { usbVendorId: 0x0403 }, // FTDI
    { usbVendorId: 0x067b }, // PL2303
  ],
  usb_c: [{ usbVendorId: 0x36b7 }],
  cable_k: [
    { usbVendorId: 0x1a86 },
    { usbVendorId: 0x10c4 },
    { usbVendorId: 0x0403 },
    { usbVendorId: 0x067b },
  ],
};

export class K5ViewerLink {
  constructor({ onStatus, onReported } = {}) {
    this.onStatus = onStatus || (() => {});
    this.onReported = onReported || (() => {});
    this.port = null;
    this.reader = null;
    this.writer = null;
    this.readLoop = null;
    this.keepAliveTimer = null;
    this.linkMode = "auto";
    this.parser = new K5ViewerParser((pkt) => {
      if (pkt.type === "reported") this.onReported(pkt);
    });
    this.closing = false;
  }

  get supported() {
    return "serial" in navigator;
  }

  get connected() {
    return this.port != null;
  }

  async connect() {
    if (!this.supported) throw new Error("Web Serial is not available in this browser");
    this.closing = false;
    const filters = LINK_FILTERS[this.linkMode] || LINK_FILTERS.auto;
    this.port = await navigator.serial.requestPort({ filters });
    await this.port.open({ baudRate: BAUD, bufferSize: 4096 });
    // Some UART adapters need DTR/RTS asserted (same as Android path).
    try {
      if (this.port.setSignals) {
        await this.port.setSignals({ dataTerminalReady: true, requestToSend: true });
      }
    } catch (_) {}
    this.writer = this.port.writable.getWriter();
    this.reader = this.port.readable.getReader();
    this.readLoop = this.#readForever();
    this.keepAliveTimer = setInterval(() => {
      this.#write(KEEP_ALIVE).catch(() => {});
    }, 200);
    await this.#write(KEEP_ALIVE);
    this.onStatus("connected");
  }

  async disconnect() {
    this.closing = true;
    if (this.keepAliveTimer) {
      clearInterval(this.keepAliveTimer);
      this.keepAliveTimer = null;
    }
    try {
      if (this.reader) {
        await this.reader.cancel().catch(() => {});
        this.reader.releaseLock();
      }
    } catch (_) {}
    try {
      if (this.writer) this.writer.releaseLock();
    } catch (_) {}
    try {
      if (this.port) await this.port.close();
    } catch (_) {}
    this.reader = null;
    this.writer = null;
    this.port = null;
    this.onStatus("disconnected");
  }

  async sendGps(latCas, lonCas) {
    await this.#write(makeGpsPacket(latCas, lonCas));
  }

  async #write(bytes) {
    if (!this.writer) throw new Error("Not connected");
    await this.writer.write(bytes);
  }

  async #readForever() {
    try {
      while (this.reader && !this.closing) {
        const { value, done } = await this.reader.read();
        if (done) break;
        if (value) this.parser.push(value);
      }
    } catch (err) {
      if (!this.closing) this.onStatus("error", err);
    } finally {
      if (!this.closing) await this.disconnect();
    }
  }
}
