# Project Instructions

- When installing Android builds on connected devices, install only for the main user (`--user 0`).
- Never install, update, or open builds in Samsung Dual App / cloned profiles such as `DUAL_APP` (`user 95`).
- If the app appears in a dual profile by mistake, remove only that profile install with `pm uninstall --user 95 <package>`.
- For Android UI in general, prefer the refined DroidBattery-style Samsung One UI inspired layout: fixed dark background, rounded dark cards, circular colored icons near the left edge, title/summary in one text column, moderate Samsung-like typography, explicit action buttons, and dark rounded dialogs.
