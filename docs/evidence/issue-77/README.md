# Office setup visual evidence (#77)

[Large-text office form](large-text-office-actions.png) is an independently captured API 37 emulator screenshot of the `b8323cf9a85781007f491cc15fa78aac59d73ffe` app source, at a 360 dp wide viewport with 200% font scale. The form uses only synthetic test state. It shows that both switch labels are readable and that full-width Save and Cancel actions remain visible and reachable after scrolling. The image SHA-256 is `599527f4f3d109bcb79b9befe740a4af9172c3a3b948347fc66eb1a23199b61a`.

This screenshot supports large-text form layout only. Synthetic address-result selection, map retry, explicit confirmation and stale-query behavior are covered by focused running-app tests. It does not show or establish geofence delivery.

[Public-address map review](public-address-map-review.png) is an independently captured API 37 emulator screenshot of the `b8052dc92f9c6cf39a5e6730e1b2e8f059cb39be` app source at 360 dp width and 200% font scale. With location permission denied, the verifier typed “Eiffel Tower,” selected the device geocoder result, and loaded OpenStreetMap tiles. The earlier single-tile review clipped the pin/radius and overlaid attribution; this repaired view shows the entire 150 m boundary, the pin inside it, attribution below the map, and a reachable Confirm action. It contains a public landmark, not a personal office. Image SHA-256: `a711f2c6b1b78db3c990b9bd01b8024728be5db4fb3366788283c151ff373d80`.

This proves the observed provider-backed search and map review on that emulator. It does not prove every address provider, a real current-location fix, TalkBack behavior, physical-phone office setup or OS geofence delivery.
