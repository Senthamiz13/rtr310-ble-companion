# RTR Link roadmap

These are planned areas of work, not released features. Each depends on protocol investigation and testing with the bike.

## Planned

- **Google Maps navigation:** Explore sending turn guidance from Google Maps to the bike display. The navigation frame and the bike's rendering behavior need validation in the current app.
- **Bike-to-phone alerts:** Investigate forwarding bike events to the phone as audio and vibration alerts, including which events the bike exposes over BLE and how reliably they can be identified.

## Exploring

- **Bike firmware:** Research whether a separate firmware project is technically feasible. No custom firmware, bike firmware update, or flashing workflow is currently available.

The roadmap may change as captures, protocol findings, and real-bike testing clarify what the bike supports. See [`bike_ble_protocol.md`](bike_ble_protocol.md) for confirmed mappings and open questions.
