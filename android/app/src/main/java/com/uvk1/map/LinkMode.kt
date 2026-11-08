package com.uvk1.map

/**
 * How the phone reaches the radio.
 *
 * USB_C  — radio USB-C CDC (VID 36B7), convenient but often noisy RF.
 * CABLE_K — USB-UART programming cable on the Kenwood K plug (CH340/CP210x/…).
 * AUTO   — prefer CDC if present, else first known UART adapter.
 */
enum class LinkMode(val label: String) {
    AUTO("Auto"),
    USB_C("USB-C (CDC)"),
    CABLE_K("Câble K (prog)"),
}
