# Office setup visual evidence (#77)

[Large-text office form](large-text-office-actions.png) is an independently captured API 37 emulator screenshot of the `b8323cf9a85781007f491cc15fa78aac59d73ffe` app source, at a 360 dp wide viewport with 200% font scale. The form uses only synthetic test state. It shows that both switch labels are readable and that full-width Save and Cancel actions remain visible and reachable after scrolling. The image SHA-256 is `599527f4f3d109bcb79b9befe740a4af9172c3a3b948347fc66eb1a23199b61a`.

This screenshot supports large-text layout only. Synthetic address-result selection, map retry, explicit confirmation and stale-query behavior are covered by focused running-app tests; a provider-backed address result, real current-location fix, TalkBack walkthrough and physical-phone office setup require separate evidence. It does not show or establish geofence delivery.
