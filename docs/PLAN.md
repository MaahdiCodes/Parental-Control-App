# Plan: "TV Guardian", a parental control app for Google TV (Hisense) and later LG

## Context

The kids watch unlimited YouTube and other streaming apps on the Hisense Google TV, and the built-in parental controls aren't working well enough. The goal is our own system where the parent's **Android phone** can:

- **See** what's playing on the TV right now (app, video title, channel, thumbnail, playing or paused).
- **Control** it remotely: pause, stop, close the app, go back or home, lock the TV with a "Time's up" screen, or play a different video.
- **Limit** it automatically: daily screen-time limits, allowed hours and bedtime, and blocked or allowed apps.
- Do all of this **from anywhere** (home Wi-Fi or far away), with the **phone and TV on different Google accounts**.
- Later (v2): AI that detects unsuitable videos for the kids' ages and alerts the parent.

Constraints from your answers: the parent phone is **Android only**, you're a **beginner** (so I write the code and the setup stays managed and simple), you want **strong tamper protection without a factory reset**, and the **LG is a later phase** because there's no device at your hometown yet.

The project folder (`h:\Personal\Pet Projects\Parental-Control-App`) is empty, so everything is new.

---

## 1. How it works (architecture)

```
 ┌─────────────────────┐        internet         ┌──────────────────────────┐
 │  PARENT PHONE APP   │ ◄────────────────────►  │  FIREBASE (Google cloud, │
 │  (Android, Kotlin)  │   commands / live state │  free tier)              │
 │  - Now Watching     │                         │  - Auth (login)          │
 │  - Remote controls  │                         │  - Firestore (database + │
 │  - Rules & limits   │                         │    real-time sync)       │
 │  - History          │                         │  - Cloud Messaging (push)│
 └─────────────────────┘                         └────────────▲─────────────┘
                                                              │ internet
                                                 ┌────────────┴─────────────┐
                                                 │  TV AGENT APP            │
                                                 │  (installed ON the       │
                                                 │   Hisense Google TV)     │
                                                 │  - reads what's playing  │
                                                 │  - executes commands     │
                                                 │  - enforces rules even   │
                                                 │    when offline          │
                                                 │  - lock screen overlay   │
                                                 └──────────────────────────┘
```

**Why this design:**

1. **Google TV is Android underneath.** We can install our own small "agent" app on the TV, and it gets real power: it can read what's playing, pause it, close apps and cover the screen. A phone-only remote-control app can't reliably do any of that.
2. **The cloud (Firebase) is the go-between.** The phone writes a command such as "pause", and the TV picks it up in under a second. This works the same at home and from far away, so we don't need port forwarding or a separate home-network mode.
3. **Google accounts don't matter.** Our app has its own pairing: the TV shows a QR code or 6-digit code and the phone scans it. Whatever Google account is signed in on the TV is never touched or needed. You sign in to the parent app with any account you like.
4. **Rules are enforced on the TV itself.** The TV keeps a copy of the rules, so limits and bedtime still work if the internet drops or a child unplugs the router.
5. **Beginner-friendly.** Firebase is managed: no servers to run, it's free at our scale, and it uses the same Google console you already know.

### Tech stack

| Part             | Technology                                                        | Why                                                                                      |
| ---------------- | ----------------------------------------------------------------- | ---------------------------------------------------------------------------------------- |
| Parent phone app | Kotlin + Jetpack Compose (Android)                                | Standard modern Android; Android only, so no cross-platform layer needed                 |
| TV agent app     | Kotlin + Compose for TV (Android TV / Google TV)                  | The only way to run code on Google TV                                                    |
| Shared code      | Kotlin module (`:core`)                                         | Data models and command definitions written once and used by both apps                   |
| Backend          | Firebase Auth, Firestore, Cloud Messaging (later Cloud Functions) | Managed, real-time, free tier                                                            |
| YouTube details  | YouTube Data API v3 (free quota)                                  | Turns a video ID into title, channel, category, "made for kids" and age-restricted flags |
| Build tool       | Android Studio, a single Gradle project                           | One project, two apps, so it's easy to open and run                                      |

---

## 2. How the TV agent sees and controls things (key Android features)

| Need                                          | Android feature used                                                                   | Notes                                                                                                                         |
| --------------------------------------------- | -------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------- |
| What app is in the foreground                 | **Accessibility Service** (window-change events)                                 | Instant and reliable                                                                                                          |
| What video is playing (title, channel, state) | **MediaSessionManager.getActiveSessions()** (needs Notification Listener access) | YouTube, Netflix, Prime and others publish "Now Playing" info here                                                            |
| YouTube video ID (fallback and extra detail)  | **YouTube "Link with TV code" (Lounge) protocol**, then the YouTube Data API     | Gives the exact video ID, so we can look up age restriction and "made for kids". Unofficial, so it's a fallback, not the base |
| On-screen text (last-resort fallback)         | Accessibility node text                                                                | Used only if the two methods above don't give a title                                                                         |
| Pause / play / stop                           | `MediaController.transportControls`, or media key events                             |                                                                                                                               |
| Close an app / go home / go back              | Accessibility `performGlobalAction(HOME/BACK)`                                       |                                                                                                                               |
| "Time's up" lock screen                       | Accessibility overlay (`TYPE_ACCESSIBILITY_OVERLAY`)                                 | Full-screen cover that the remote can't dismiss. Unlocks only with the parent's PIN or from the phone                         |
| Play a specific video                         | `Intent(ACTION_VIEW, youtube URL)` sent to the YouTube TV app                        |                                                                                                                               |
| Show a message on the TV                      | Overlay toast or banner                                                                | For example "Dinner time! TV locks in 5 min"                                                                                  |
| Survive reboots                               | Boot receiver plus a foreground service                                                |                                                                                                                               |
| Block uninstall                               | **Device Admin**                                                                 | Our app can't be uninstalled until admin is turned off, which the guard below blocks                                          |

**One-time laptop setup (ADB).** Google TV hides some permission screens, so during setup we connect a laptop to the TV over Wi-Fi once and run a script I'll write (`tools/setup-tv.ps1`). It grants Notification Listener access, turns on the Accessibility service and activates Device Admin. This takes about 5 minutes and never needs a factory reset.

---

## 3. Feature list

### v1 (this plan)

**Pairing and devices**

- Pair the TV and phone with a QR code or 6-digit code (the two Google accounts don't matter).
- Name each TV, for example "Living room Hisense", and see online/offline status and last-seen time.
- Check protection health: Accessibility ON, Device Admin ON and Notification access ON. The phone warns you if any of these is OFF.

**Live monitoring**

- "Now Watching" card: app icon, video title, channel, thumbnail, play/pause state and how long it's been on.
- Watch history: a list of what was watched, when and for how long, grouped by day.
- Daily usage totals per app.

**Remote control (from the phone)**

- Pause, Play and Stop.
- Close the current app or send the TV to the home screen.
- **Lock TV now** (the "Time's up" screen), and Unlock.
- **Play this instead:** paste a YouTube link, or pick from a "parent-approved" list.
- Send a message to the TV screen.
- Volume down or mute.
- Add 15 or 30 more minutes (bonus time).

**Rules (enforced on the TV, so they work offline)**

- Daily screen-time limit, for example 2 hours on weekdays and 3 hours on weekends.
- Allowed-hours schedule and bedtime: the TV locks outside allowed hours.
- App rules: block specific apps, or an **allow-list mode** where only chosen apps can open (for example YouTube Kids only).
- Per-app time limit, for example YouTube for at most 1 hour a day.
- Warning banners 10 and 5 minutes before a lock.
- Optional simple YouTube filter: block videos that YouTube marks age-restricted, plus a keyword or channel block list.

**Parent PIN on the TV**

- A 4–6 digit PIN unlocks the lock screen on the TV and opens the agent's settings.

### v2 (future; design now, build later)

- AI content check: video title, description, thumbnail and (if available) transcript are sent to an AI model (for example Claude Haiku, `claude-haiku-5-5`) with the child's age, and it returns OK, caution or not suitable.
- Push alerts to the phone such as "Unsuitable video detected", "Limit reached" or "TV agent went offline", with the option to auto-stop.
- Weekly report: top channels, time trends and flagged content.
- Profiles per child, by age.
- LG and other TVs (see Phase 5).

---

## 4. Database design (Firestore)

```
families/{familyId}
    ownerUid, parentPinHash, createdAt
families/{familyId}/devices/{deviceId}
    name, type: "googletv" | "lg", model, osVersion, agentVersion,
    agentUid, online, lastSeen,
    protection: { accessibility, deviceAdmin, notificationAccess }
families/{familyId}/devices/{deviceId}/state/now
    packageName, appName, title, channel, videoId, thumbnailUrl,
    playbackState, positionSec, startedAt, updatedAt
families/{familyId}/devices/{deviceId}/commands/{commandId}
    type: PAUSE|PLAY|STOP|HOME|BACK|CLOSE_APP|LOCK|UNLOCK|PLAY_URL|MESSAGE|VOLUME|BONUS_TIME
    args, createdAt, createdBy, status: pending|done|failed, result
families/{familyId}/devices/{deviceId}/rules (single doc)
    dailyLimitMin { weekday, weekend }, schedule [ {days, start, end} ],
    appMode: "blocklist" | "allowlist", apps { pkg: allowed|blocked|limitMin },
    youtube { blockAgeRestricted, blockedKeywords[], blockedChannels[] },
    bonusMinutesToday
families/{familyId}/devices/{deviceId}/history/{sessionId}
    packageName, title, channel, videoId, start, end, durationSec
families/{familyId}/devices/{deviceId}/usage/{yyyy-MM-dd}
    totalSec, perApp { pkg: sec }
pairingCodes/{code}
    deviceId, agentUid, expiresAt, claimedByFamilyId
```

**Security rules (`firebase/firestore.rules`):**

- Only the family's parent account can read the family's data and write commands and rules.
- A TV can only read its own commands and rules, and only write its own state, history and usage. This is checked by `agentUid == request.auth.uid` on its device doc, so no Cloud Functions are needed for v1.
- Pairing codes expire after 10 minutes and can be claimed only once.

**Pairing flow:**

1. The TV agent signs in to Firebase anonymously.
2. It creates `pairingCodes/123456` and shows a QR code and the code on the TV screen.
3. On the parent phone (signed in with **your** Google account, whichever one you choose), you scan the QR code. The phone creates the device doc under your family and marks the code claimed.
4. The TV sees the claim, saves the `familyId`, and starts listening for commands and rules.

---

## 5. Project structure

```
Parental-Control-App/
├── settings.gradle.kts, build.gradle.kts, gradle/libs.versions.toml
├── core/                      # shared Kotlin: models, command types, rule engine (pure Kotlin, unit-tested)
│   └── src/main/kotlin/.../core/{model, commands, rules/RuleEngine.kt}
├── app-parent/                # phone app
│   └── src/main/kotlin/.../parent/
│       ├── auth/              # Google sign-in
│       ├── pairing/           # QR scanner (CameraX + ML Kit barcode)
│       ├── devices/           # device list, protection health
│       ├── nowwatching/       # live card + remote control buttons
│       ├── rules/             # limits, schedule, app rules editors
│       └── history/           # watch history & usage charts
├── app-tv/                    # TV agent
│   └── src/main/kotlin/.../tv/
│       ├── AgentService.kt            # foreground service: Firestore listeners, heartbeat
│       ├── GuardAccessibilityService.kt  # foreground app detection, HOME/BACK, overlays, settings guard
│       ├── MediaWatcher.kt            # NotificationListener + MediaSession reading/control
│       ├── YouTubeLounge.kt           # fallback: video ID via "Link with TV code"
│       ├── Enforcer.kt                # applies RuleEngine decisions (lock/block/warn)
│       ├── LockOverlay.kt             # "Time's up" screen + PIN pad
│       ├── AdminReceiver.kt           # Device Admin (uninstall protection)
│       ├── BootReceiver.kt
│       └── ui/                        # pairing screen (QR), PIN-protected settings
├── firebase/                  # firestore.rules, firestore.indexes.json, (later) functions/
├── tools/
│   └── setup-tv.ps1           # one-time ADB setup script for the TV
└── docs/                      # setup guide for you (screenshots/steps)
```

The **rule engine** lives in `core/` as plain Kotlin: given the time, today's usage, the rules and the foreground app, it returns a decision such as ALLOW, WARN(5 min), BLOCK_APP or LOCK. Because it's pure logic, it gets thorough unit tests, and later the LG hub can reuse the same engine.

---

## 6. Build phases (step by step)

Each phase ends with something you can test on the real TV and phone.

### Phase 0: Setup and feasibility test (important; do first)

**You do (I'll guide you step by step):**

1. Install **Android Studio** on the Windows PC.
2. Check the TV's OS version: Settings → System → About. Confirm it says "Android TV OS" with version 11 or newer.
3. Turn on **Developer options** on the TV: About → click "Android TV OS build" 7 times. Then turn on **USB debugging / Network debugging**.
4. Create a free **Firebase project** in the Firebase console and add two Android apps (phone and TV). Download `google-services.json`.
5. Get a **YouTube Data API key** from Google Cloud Console (free).

**I build:** a tiny test version of the TV agent that just logs the foreground app and the MediaSession title, and tries pause and the lock overlay.

**Goal:** prove on *your* Hisense that we can read YouTube and Netflix titles and pause them. If MediaSession doesn't give titles for YouTube, we switch on the Lounge fallback. This de-risks everything else before we build the full app.

### Phase 1: Project skeleton and pairing

- Gradle multi-module project (`core`, `app-parent`, `app-tv`).
- Firebase Auth: Google sign-in on the phone, anonymous sign-in on the TV.
- Pairing via QR code and 6-digit code, the devices list, and online/offline heartbeat (TV updates `lastSeen` every 60s).
- Firestore security rules.
- ✅ **Test:** pair the TV, see "Living room Hisense: Online" on the phone, unplug the TV, and see it go Offline within about 3 minutes.

### Phase 2: Now Watching and remote control

- TV: `MediaWatcher` and the Accessibility service publish `state/now` and record history sessions.
- TV: command listener that executes PAUSE, PLAY, STOP, HOME, BACK, CLOSE_APP, MESSAGE, PLAY_URL and VOLUME, and reports done or failed.
- Phone: live Now Watching card, control buttons, and watch history screen.
- ✅ **Test:** play a YouTube video and the title appears on the phone within about 2 seconds. Press Pause on the phone and the TV pauses. Send a YouTube link and it plays. Send a message and it shows on the TV.

### Phase 3: Rules, limits and the lock screen

- `core/RuleEngine` with unit tests (limits, schedule, weekday/weekend, bonus time, midnight rollover, time-zone edge cases).
- TV: `Enforcer` runs every 30s and on every app change. It shows the lock overlay, blocks apps (sends to HOME and shows a "This app is blocked" banner), and shows warning banners.
- TV: usage tracking per day and per app, saved locally (Room database) and synced to Firestore.
- TV: Parent PIN pad on the overlay.
- Phone: rules editor screens, Lock/Unlock now, and the +15/+30 min bonus.
- ✅ **Test:** set a 5-minute limit, watch, and the warning then lock appear on time. Unlock with the PIN. Put the TV in blocked hours and it locks. Open a blocked app and it gets kicked to home. Turn off the Wi-Fi and limits still apply.

### Phase 4: Tamper protection and polish ("Strong protection")

- **Device Admin**: the app can't be uninstalled while admin is active.
- **Settings guard** (via Accessibility): while the TV isn't parent-unlocked, opening Settings → Apps → TV Guardian, Device admin apps, Accessibility, Developer options or Factory reset sends the TV back to home. The parent PIN unlocks Settings for 10 minutes.
- **Self-healing**: boot receiver, foreground service restart, and a watchdog.
- **Health reporting**: the TV reports if any permission was turned off, and the phone shows a red warning.
- **Offline policy (optional)**: if the TV can't reach the cloud for more than X hours, it still enforces cached rules; optionally it locks after a long offline period.
- `tools/setup-tv.ps1`: a one-click ADB script that grants Notification Listener, enables Accessibility, activates Device Admin, then reminds you to **turn developer options OFF** afterward so the kids can't use them.
- Recommended extra settings on the TV itself (defence in depth): a PIN on the Play Store for app installs, "Install unknown apps" turned off, and YouTube **Restricted Mode** on.
- ✅ **Test:** try to uninstall the app, disable it, or open the Accessibility settings as a kid would. Each is blocked, and the phone shows an alert. Reboot the TV and the agent comes back on its own.

### Phase 4.5: Push notifications (needs a Firebase pay-as-you-go plan; still about $0 at our scale, with a $1 budget alert)

- Cloud Functions send alerts to the phone even when the app is closed: "TV offline for 10 min", "Protection disabled", "Daily limit reached" and "Blocked app attempted".
- ✅ **Test:** close the phone app, trigger each event, and a notification arrives.

### Phase 5: LG TV at the hometown (later)

LG TVs run webOS, which **can't run our agent app**. There are two options:

- **Option A, recommended (least work, full features):** buy a Google TV Streamer or Chromecast with Google TV for about $30–100, plug it into the LG, and install the same TV agent on it. Then use LG's own *App Lock* (Settings → General → Safety) with a PIN to lock the LG's built-in YouTube and Netflix, so the kids must use the stick. The result is identical to the Hisense.
- **Option B (no new TV hardware, fewer features):** add a "Hub mode" app (`app-hub`) running on an old Android phone that stays plugged in on the hometown Wi-Fi. It controls the LG over LG's local network API (the same one Home Assistant and LG's own app use): it reads the foreground app, closes apps, turns the TV off, shows on-screen toasts, and pauses and plays. It reads YouTube titles via the "Link with TV code" protocol. It reuses the same `core/RuleEngine`, but a "lock" means turning the TV off, because we can't draw a lock screen on webOS.

### Phase 6 (v2): AI content alerts (future)

- The TV sends `{title, channel, description, thumbnail, child age}` to a Cloud Function, which calls the AI model. The model returns OK, caution or not suitable, with a reason.
- If the video is flagged, the parent gets a push alert with **Stop** and **Allow** buttons. An optional auto-stop rule blocks it right away.
- An "Approved channels" learning list cuts repeated AI checks, which keeps cost low.

---

## 7. Honest limitations and risks

| Risk                                                                    | Mitigation                                                                                                                                                                                     |
| ----------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Some apps may not publish titles to MediaSession                        | Phase 0 test first. Fallbacks are the YouTube Lounge protocol and on-screen text                                                                                                               |
| The YouTube Lounge protocol is unofficial and could change              | Used only as a fallback. The core features don't depend on it                                                                                                                                  |
| Google TV updates could change permission behaviour                     | The health check warns the parent, and the setup script can be re-run                                                                                                                          |
| A determined older child could factory-reset the TV                     | The agent goes offline, and the parent sees it (and gets an alert in Phase 4.5). A factory reset also wipes their own data. "Maximum" Device Owner mode is possible later if this ever happens |
| The TV can't truly power itself off from an app                         | The lock overlay works as "off" (black screen with a message). A real power-off is attempted where the TV allows it                                                                            |
| We can't stop a child from using a different device (a tablet or phone) | Out of scope. Google Family Link covers phones and tablets                                                                                                                                     |

**Privacy:** all data stays in **your own** Firebase project, and nothing goes to third parties (in v2, the video title is sent to the AI only for classification).

---

## 8. What you'll need

\

- A Windows PC with Android Studio (free).
- The Hisense Google TV on Wi-Fi, with Developer options turned on temporarily for setup.
- Your Android phone (for testing; a USB cable helps).
- A Google account for the Firebase console (free) and a YouTube Data API key (free).
- Later: a Firebase pay-as-you-go plan for push alerts (effectively $0), and for the LG either a Google TV stick or an old Android phone.

---

## 9. Verification (end-to-end acceptance checklist)

1. **Unit tests**: `./gradlew :core:test`, where the RuleEngine tests pass (limits, schedules, bonus, midnight, weekend).
2. **Install**: the phone app from Android Studio, and the TV app via `adb connect <TV-IP>:5555` then `adb install`. Then run `tools/setup-tv.ps1`.
3. **Pairing**: scan the QR code. The TV appears Online on the phone, and the protection health is all green.
4. **Monitoring**: play YouTube, then Netflix. The correct titles appear on the phone, and history entries are recorded.
5. **Controls**: Pause, Play, Stop, Home, Close app, Message, Play URL and Lock/Unlock each work within about 2 seconds, including from mobile data (away from home Wi-Fi).
6. **Rules**: a 5-minute limit triggers the warning and lock. A schedule lock works. A blocked app is kicked out. Rules still enforce with the Wi-Fi off.
7. **Tamper**: uninstall, disable and Accessibility-off attempts are blocked or reported. After a reboot, the agent auto-starts.
8. **Different accounts**: confirm the TV's Google account is different from the phone's and everything still works.
9. **Firestore rules**: the Firebase Emulator rules tests pass, so a second (non-family) account can't read your TV's data or send commands.

---

## 10. Suggested order of work with me

We go phase by phase: **Phase 0 → 1 → 2 → 3 → 4**, which gives a fully working v1 on the Hisense, then 4.5 (push), then 5 (LG), then 6 (AI). After each phase you test on the real TV, and we fix anything before moving on. I'll also write `docs/SETUP.md` with plain step-by-step instructions for every manual step (Android Studio, Firebase, TV developer mode, running the setup script).
