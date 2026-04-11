# Dronzer: Android Accessibility Research Framework
Dronzer is an advanced Android-based research framework designed to explore the capabilities and security implications of the AccessibilityService API. It demonstrates how system-level permissions can be leveraged for automated data collection, UI hierarchy analysis, and remote telemetry.
[!CAUTION] Educational & Ethical Use Only: This project is intended strictly for academic research, authorized security auditing, and accessibility testing. Unauthorized use of this framework to monitor devices without explicit user consent is illegal and violates privacy policies.
# 🏗 Framework Architecture
Dronzer is built as a modular background service that intercepts system events and exfiltrates data through multiple redundant channels.
1. Intelligence Engine (AccessibilityService)
The core of the framework monitors the Android UI layer in real-time:
•
Keylogging: Intercepts TYPE_VIEW_TEXT_CHANGED and TYPE_VIEW_FOCUSED events to capture user input.
•
Interactive Scraping: Uses a recursive tree-traversal algorithm (AccessibilityNodeInfo) to scrape text from the active window, preserving the visual hierarchy and layout context.
•
Event Filtering: Intelligent package filtering ensures the service does not log its own activity or system-critical loops.
2. Multi-Channel Data Exfiltration
•
Firebase Integration: Real-time synchronization of captured logs and device status to a Firebase Realtime Database.
•
Discord Webhook & Bot API: A dual-mode communication layer (DiscordWebhookSender.kt) that can send reports via standard Webhooks or the Discord Bot API (v10).
•
Resilience Layer: Local file-based caching allows the framework to recover and upload logs that failed to send during network interruptions.
3. Secure Control Interface
•
Terminal-Style UI: A Jetpack Compose-based dashboard using a "Cyber-Red" terminal aesthetic.
•
Biometric Security: Integration with the androidx.biometric library to gate access to the framework settings.
🛠 Technical Stack
•
Language: Kotlin 2.2.10
•
UI Framework: Jetpack Compose (Material 3)
•
Networking: OkHttp 4.11.0
•
Backend: Firebase BOM (Firestore, Realtime Database, Analytics, Storage)
•
Async: Kotlin Coroutines & WorkManager for background persistence.
# 🚀 Getting Started
1. Configuration
The framework reads its configuration from SharedPreferences. For the system to function, you must provide:
•
firebase_url: Your Firebase Database instance.
•
webhook_url: A Discord Webhook URL.
•
bot_token / channel_id: (Optional) For Bot API reporting.
2. First-Time Login
By default, the application is locked behind an authentication screen:
•
Username: admin
•
Password: admin
•
Note: These should be changed in the source or via a configuration update before deployment.
3. Enabling the Service
After installation, the framework will not start automatically. You must manually grant permissions: Settings > Accessibility > Downloaded Apps > Dronzer > Enable
# 📂 Project Structure
Java
com.example.dronzer/
├── DiscordWebhookSender.kt   # Discord API communication logic
├── DronzerAccessibility.kt   # Core AccessibilityService implementation
├── AuthenticationScreen.kt   # Biometric & Credential-based UI
├── DeviceInfoSender.kt       # System telemetry collection
└── ...
# 📜 License
Distributed under the MIT License. See LICENSE for more information.
Disclaimer: The developers of Dronzer are not responsible for any misuse of this software. Always adhere to the laws of your jurisdiction.
