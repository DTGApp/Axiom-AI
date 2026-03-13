AXIOM
Privacy Policy
Effective Date: [13.Mar.2026]   ·   Last Updated: [13.Mar.2026]
App Package: com.axiom.axiomnew   ·   Developer: [Rayad]
Contact: [dtgapp24@gmail.com]
1. Overview
Axiom is an on-device AI assistant for Android. It learns your daily habits and app usage patterns to offer proactive suggestions, intelligent phone mode switching, and personal insights — entirely on your device.
The most important thing to understand about Axiom's privacy model:

All data collected by Axiom is processed and stored exclusively on your device. No personal data, usage patterns, app history, or inference results are ever transmitted to any server, cloud service, or third party.

This policy explains what data Axiom accesses, why it needs it, where it is stored, and your rights over it.
2. Data Axiom Accesses On Your Device
Axiom accesses the following categories of data. All processing happens locally using an on-device language model (LLM). Nothing is sent off your phone.
2.1  App Usage & Screen Activity
	•	What: Which apps you open, when you open them, and how long you use them.
	•	Why: To learn your habits, power the Morning Ritual feature, predict what you may need next, and surface insights in the "What Axiom Knows" screen.
	•	Permission required: Usage Access (granted via Android Settings > Apps > Special app access > Usage access).
	•	Stored in: axiom_events.jsonl and axiom_preds.csv — local files in Axiom's private app directory, not accessible to other apps.

2.2  Notifications
	•	What: Notification content from other apps, used to understand context (e.g. an incoming message while you are driving).
	•	Why: To power proactive suggestions such as detecting when you are likely commuting or in a meeting.
	•	Permission required: Notification Listener Service (granted via Android Settings > Notifications > Notification access).
	•	Stored in: Processed transiently in memory only. Notification text is passed to the on-device LLM and the result is stored, not the original notification content.

2.3  Call Log
	•	What: A list of recent calls (incoming, outgoing, missed) and their timestamps.
	•	Why: To detect calling patterns (e.g. you call family on Sunday evenings) and improve contextual predictions.
	•	Permission required: READ_CALL_LOG.
	•	Stored in: Call metadata (frequency, time-of-day patterns) may be stored in axiom_events.jsonl. Raw call log entries are never persisted by Axiom.

2.4  Location Context
	•	What: Coarse location (city/region level) and network-based location context.
	•	Why: To detect transitions between places (home, work, commute) for automatic phone mode switching.
	•	Permission required: ACCESS_COARSE_LOCATION. Axiom does not request or use precise GPS location (ACCESS_FINE_LOCATION).
	•	Stored in: Location state transitions are logged in axiom_events.jsonl. Raw coordinates are never persisted.

2.5  Device State & Sensors
	•	What: Battery level, charging state, Bluetooth status (on/off), Wi-Fi connectivity (connected/disconnected — not network name or traffic), screen state.
	•	Why: These signals are the foundation of the phone mode system (Sleep, Commute, Work, Focus, Gaming, Normal) and the Battery Doctor feature.
	•	Permission required: Standard Android permissions (BATTERY_STATS, BLUETOOTH, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE). No special permissions required.
	•	Stored in: Mode switch log in SharedPreferences (local only).

2.6  Installed Applications
	•	What: The list of apps installed on your device.
	•	Why: To filter out system apps and launchers from usage analysis, resolve app names for display, and identify dormant apps in the profile screen.
	•	Permission required: QUERY_ALL_PACKAGES (declared in manifest).
	•	Stored in: Not persisted. Queried at runtime only.

2.7  Contacts (Metadata Only)
	•	What: Contact names, used to identify calling patterns (e.g. frequent contacts).
	•	Why: To improve context understanding for proactive suggestions (e.g. "you usually call [name] on Friday afternoons").
	•	Permission required: READ_CONTACTS.
	•	Stored in: Contact identifiers (not names) may be referenced in axiom_events.jsonl. Contact names are never stored in plain text by Axiom.

2.8  Audio (File Search Feature Only)
	•	What: Microphone access is used only when you explicitly tap the voice input button in the File Search or main assistant features.
	•	Why: To transcribe your spoken query. Audio is processed entirely on-device using the on-device LLM. No audio is recorded passively.
	•	Permission required: RECORD_AUDIO.
	•	Stored in: Never. Audio is transcribed in real time and immediately discarded.
3. On-Device AI & The Language Model
Axiom uses a quantised large language model (LLM) stored locally on your device (the model file axiom_seed_q4.gguf in Axiom's private app directory). All AI inference — including intent classification, proactive suggestions, battery diagnosis, and mode decisions — runs on this local model.
The model does not connect to the internet. It does not send your data to Anthropic, OpenAI, Google, or any other AI provider. There is no cloud inference.
The model file itself is a general-purpose AI base model. It is not pre-trained on your personal data and does not "phone home" to update itself. Axiom's personalisation is achieved through a local adapter file (axiom_adapter.bin) that is updated on your device as you interact with Axiom.
4. Local Data Storage
All data Axiom stores is held in its private app directory on your device. This directory is not accessible to other apps (unless your device is rooted). The files are:

	•	Model file: axiom_seed_q4.gguf
	•	Personalisation adapter: axiom_adapter.bin
	•	Event log: axiom_events.jsonl
	•	Prediction table: axiom_preds.csv
	•	Settings & mode log: SharedPreferences (axiom_mode, axiom_profile)

None of these files are backed up to Google Drive, synced to any cloud service, or shared with any third party.
Note: If you use Android's built-in backup feature and have not excluded Axiom, SharedPreferences may be backed up to your Google account. You can disable this in Android Settings > System > Backup. The large model and event files are excluded from backup by default due to their size.
5. Data Axiom Does NOT Collect
To be explicit, Axiom does not collect, transmit, or share:
	•	The content of your messages, emails, or documents.
	•	Photos, videos, or media files (File Search reads metadata and filenames only — never file content).
	•	Precise GPS location or location history.
	•	Your Google account, Apple ID, or any login credentials.
	•	Financial data, health data, or biometric data.
	•	Any data that leaves your device, ever.
	•	Advertising identifiers (Axiom contains no ads and no ad SDKs).
	•	Analytics or crash data sent to third-party services.
6. Permissions — Detailed Justification
Google Play requires apps to justify each sensitive permission. The following table summarises Axiom's permission usage:

READ_CALL_LOG — Core feature. Used to detect calling habits and improve contextual predictions. Axiom is the primary beneficiary. No call content is accessed.
BIND_NOTIFICATION_LISTENER_SERVICE — Core feature. Used to detect context (commute, meeting, focus). Notification text is processed transiently on-device only.
PACKAGE_USAGE_STATS — Core feature. Used for habit detection, Morning Ritual, and the What Axiom Knows screen.
RECORD_AUDIO — Used only on explicit user action (voice input button). Never passive.
ACCESS_COARSE_LOCATION — Used for place-transition detection in phone mode switching only. Fine location not used.
READ_CONTACTS — Used for contact pattern detection only. Contact names are never stored.
QUERY_ALL_PACKAGES — Used to resolve app names and filter system apps from usage analysis.
7. Children's Privacy
Axiom is not directed at children under 13. We do not knowingly collect personal information from children. If you believe a child has used Axiom and data has been collected, please contact us at the email address below so we can delete it.
8. Security
All data Axiom stores is held in Android's private app sandbox, protected by Android's standard security model. Axiom does not implement any additional encryption on its local files beyond what Android provides by default (file-based encryption on supported devices).
Because no data is transmitted over the network, there is no risk of interception in transit. The attack surface is limited to physical access to your unlocked device.
9. Your Rights & How to Delete Your Data
You have full control over all data Axiom holds. You can:

	•	Clear all Axiom data at any time via Android Settings > Apps > Axiom > Storage > Clear Data.
	•	Uninstall Axiom. Uninstalling the app permanently deletes all local files including the model, adapter, event log, and all learned patterns.
	•	Revoke individual permissions at any time via Android Settings > Apps > Axiom > Permissions. Revoking a permission disables the related feature but does not delete previously analysed data.

Because no data is stored outside your device, there is no server-side data to request or delete.
10. Third-Party Services & SDKs
Axiom does not include any third-party advertising SDKs, analytics SDKs, or tracking libraries. The app does not make any network requests to external services during normal operation.
The app is distributed via Google Play. Google's own data collection practices (as the distribution platform) are governed by Google's Privacy Policy, not this document.
11. Changes to This Policy
If this privacy policy is updated, the new version will be published at the same URL and the "Last Updated" date at the top of this document will be changed. Significant changes will be communicated via an in-app notice.
Continued use of Axiom after a policy update constitutes acceptance of the revised policy.
12. Contact
If you have any questions about this privacy policy or Axiom's data practices, please contact:

Rayad
Email: dtgapp24@gmail.com



This privacy policy was written specifically for Axiom (com.axiom.axiomnew) and reflects the app's actual technical implementation. It is intended to satisfy Google Play's Data Safety requirements and applicable privacy regulations including GDPR and CCPA.
