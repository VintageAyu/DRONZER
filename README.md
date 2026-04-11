<h2> 𝐇𝐞𝐥𝐥𝐨 𝐭𝐡𝐞𝐫𝐞, Devs! <img src="https://github.com/ABSphreak/ABSphreak/blob/master/gifs/Hi.gif" width="30px"></h2>

<div align="center" width="50">

<img src="https://media.tenor.com/D6mWe50lEJ4AAAAi/bravo-bravo-supermarket.gif" alt="Welcome!" width="100"/>

</div>

<div align="center">

[<img src="https://img.shields.io/badge/twitter-%231DA1F2.svg?&style=for-the-badge&logo=twitter&logoColor=white">](https://x.com/thedevayu)
[<img src="https://img.shields.io/badge/linkedin-%230077B5.svg?&style=for-the-badge&logo=linkedin&logoColor=white">]()
[<img src="https://img.shields.io/badge/instagram-%23E4405F.svg?&style=for-the-badge&logo=instagram&logoColor=white">](https://www.instagram.com/theayuchauhan)
[<img src="https://img.shields.io/badge/facebook-%231877F2.svg?&style=for-the-badge&logo=facebook&logoColor=white">]()
[<img src="https://img.shields.io/badge/Portfolio-%23000000.svg?&style=for-the-badge">](https://VintageAyu.github.io/)

</div>

# **DRONZER : Next-Gen Android RAT 🐺**
DRONZER is an advanced Android Remote Administration Tool (RAT) engineered to stress-test the security architectures of Android 15 and later. While modern OS updates introduce "Private Spaces" and enhanced sandboxing, DRONZER explores the limits of persistent access, data exfiltration, and UI-layer exploitation for educational and red-teaming purposes.

> [!WARNING] 

LEGAL DISCLAIMER: This software is for authorized security testing and research only. Deploying DRONZER on devices without explicit, written permission is strictly prohibited and likely a felony. Proceed with clinical objectivity.

⚡ Adversarial Capabilities
DRONZER isn't just a monitor; it's designed to bypass modern system constraints:

## **1. Advanced Persistence & Bypassing**
A15 Sandbox Navigation: Specifically optimized to maintain service stability despite Android 15's aggressive background execution limits.

Accessibility Hijacking: Exploits the AccessibilityService API to perform real-time screen scraping and keylogging without requiring root access.

Silent Telemetry: Periodically gathers system metadata, app usage patterns, and hardware identifiers (IMEI, Battery, Network state) and pushes them to remote listeners.

## **2. Multi-Vector Command & Control (C2)**
Discord C2 Integration: Uses Discord as a stealthy, high-bandwidth C2 server. By leveraging the Discord Bot API, the framework can receive commands and dump logs into organized channels.

Firebase Real-time Sync: Acts as a secondary, low-latency data bridge for immediate interaction logs.

Redundant Uplinks: Automatically switches between Firebase and Discord if one endpoint is throttled or blocked.

## 3. "Cyber-Red" HUD & Terminal
Encrypted Admin Portal: A Jetpack Compose-based interface featuring a cyberpunk aesthetic, locked behind biometric authentication.

Live Feed: Monitor incoming data streams directly from the "Control" device using a custom-built terminal emulator.
