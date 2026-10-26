# TV Guardian: setup guide (Phase 0)

Phase 0 checks that **your** Hisense Google TV lets our app:

1. see which app is open,
2. read the title of what's playing (YouTube, Netflix, ...),
3. pause and play it,
4. cover the screen with a lock screen the remote can't close,
5. send the TV to the home screen.

If all five work, the full app (see [PLAN.md](PLAN.md)) will work on this TV.

We build from VS Code. **Android Studio is not needed.**

---

## Step 1: Build tools (already done on this PC)

These are already installed here. They're listed in case you set up another PC:

1. Java (JDK 17): `winget install --id Microsoft.OpenJDK.17 -e`
2. Android SDK at `%LOCALAPPDATA%\Android\Sdk`, with these packages added:
   `sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"`

## Step 2: Build the TV app

In VS Code press **Ctrl+Shift+B** (runs the "Build TV app" task), or in the terminal run:

```powershell
powershell -ExecutionPolicy Bypass -File tools\build.ps1
```

The first build takes a few minutes; later builds take seconds. The result is `app-tv\build\outputs\apk\debug\app-tv-debug.apk`.

If anything goes red, copy the error text to me.

## Step 3: Check the TV's software version

On the TV: **Settings → System → About**. Look for **Android TV OS version**. It should be **11 or newer**. Tell me the exact version and the TV model (also on that screen).

## Step 4: Turn on developer mode and debugging on the TV

1. **Settings → System → About**, scroll to **Android TV OS build** and press OK on it **7 times**. You'll see *"You are now a developer"*.
2. Go back to **Settings → System → Developer options** and turn **USB debugging** ON.
3. Then do whichever of these your TV has:
   - **Wireless debugging** (most Google TVs): turn it ON and open it. Note the **IP address & port** shown (for example `192.168.1.50:41234`). Then choose **Pair device with pairing code** and note the **pairing IP:port** and the **6-digit code**. Keep that pairing screen open for the next step.
   - **Network debugging**: turn it ON. Find the TV's IP address under **Settings → Network & Internet → (your Wi-Fi)**.

The PC and the TV must be on the **same Wi-Fi network**.

## Step 5: Install the app on the TV and grant permissions

Open **PowerShell** in the project folder (in File Explorer, open the folder, type `powershell` in the address bar and press Enter), then run **one** of these:

```powershell
# If the TV has "Wireless debugging" (use your own numbers from step 4):
powershell -ExecutionPolicy Bypass -File tools\setup-tv.ps1 -Address 192.168.1.50:41234 -PairAddress 192.168.1.50:37099 -PairCode 123456

# If the TV has "Network debugging":
powershell -ExecutionPolicy Bypass -File tools\setup-tv.ps1 -Address 192.168.1.50
```

If the TV asks **"Allow debugging?"**, tick **Always allow from this computer** and press **OK**.

When it finishes, **TV Guardian** opens on the TV and both lines at the top should say **ON**:

```
Accessibility: ON
Notification access: ON
```

(Pairing is only needed the first time. After that, just use `-Address`.)

## Step 6: Run the test

1. On the TV, in TV Guardian, press **▶ Run automatic test**.
2. Within **20 seconds**, press the remote's **Home** button, open **YouTube** and start **any video**.
3. Wait and watch. Over the next ~35 seconds:
   - the video should **pause**, then **resume**,
   - a black **"Time's up!"** screen appears for 20 seconds. **While it's up, try pressing BACK, HOME and other remote buttons.** It should not go away.
   - finally the TV goes to the **home screen**.
4. Open **TV Guardian** again and read the log at the bottom.

Then do a few quick manual checks:

- Play something in **Netflix** (and/or YouTube Kids, Prime Video, whatever the kids use), come back to TV Guardian, and see if **Now playing** shows the right title.
- Press **Read screen text in 10s**, switch to YouTube while a video is playing, wait, then come back and check the log.

## Step 7: Send me the results

From PowerShell in the project folder:

```powershell
powershell -ExecutionPolicy Bypass -File tools\pull-log.ps1 -Address 192.168.1.50:41234
```

This saves the full log to `spike-log.txt` in the project folder, and I can read it from there. Also tell me:

| Check | Worked? |
|---|---|
| Video **paused** and **resumed** by itself | |
| Black lock screen appeared and **BACK/HOME didn't close it** | |
| TV went to **home screen** at the end | |
| **YouTube** title shown correctly | |
| **Netflix / other apps** titles shown correctly | |

---

## Optional now, needed in Phase 1

These are free and need no credit card:

1. **Firebase project**: go to <https://console.firebase.google.com>, choose **Add project**, name it `tv-guardian`, and you can turn Google Analytics off. We'll add the apps to it together in Phase 1.
2. **YouTube Data API key**: go to <https://console.cloud.google.com>, select the same `tv-guardian` project, then **APIs & Services → Library**, search **YouTube Data API v3** and **Enable** it, then **Credentials → Create credentials → API key**. Keep the key private.

## Safety note

Leave **Developer options** on while we're building (we need it to install new versions). In Phase 4 the setup script will remind you to turn it **off**, so the kids can't use it.
