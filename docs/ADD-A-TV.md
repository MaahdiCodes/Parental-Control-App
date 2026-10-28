# Adding a TV

There are two kinds of TV, and they're set up differently.

| | Google TV / Android TV (e.g. the Hisense) | LG, Samsung and other TVs |
|---|---|---|
| Install anything on the TV? | **Yes**: the TV Guardian TV app | **No**: nothing can be installed |
| What you can do | Everything: see and control any app, lock, messages, voice, blocking | **YouTube only**: see what's playing, pause/play, volume, change video, block YouTube, block videos/channels, history |
| Needs this PC? | Once, for setup | No |

The phone app is the same for both: **TV-Guardian-parent.apk** (on the PC's Desktop). Install it once on your phone; it handles every TV.

---

## LG (or Samsung, Sony, ...): YouTube link, 5 minutes, no PC

Nothing gets installed on the LG. Your phone links to the LG's own YouTube app.

1. On the **LG**, open **YouTube**, then **Settings** (gear icon, bottom left), then **Link with TV code**. A 12-digit code appears.
2. On your **phone**, open TV Guardian, tap **Add TV**, choose **LG / other TV**, type the code, name it (e.g. "LG TV hometown"), tap **Pair**.
   - Codes expire after a few minutes: if it's refused, get a fresh code on the TV.
   - You can do this from anywhere: someone at home can read the code to you over the phone.
3. Allow notifications if Android asks. A small "Watching YouTube on your linked TV" notification stays on.
4. On your phone: **Settings → Apps → TV Guardian → Battery → Unrestricted**, so Android never stops the link.
5. On the **LG** (recommended, since only YouTube is covered):
   - **Settings → General → Safety**: turn it on with a PIN, and lock the apps you don't want used (Netflix, browser, other video apps, LG Channels).
   - In the LG's **YouTube** settings, turn on **Restricted Mode**.

The TV shows as **"YouTube open"** while YouTube is running on it, and **"YouTube closed"** otherwise.

## Another Google TV / Android TV: TV Guardian app, about 20 minutes, needs this PC

Includes Chromecast with Google TV / Google TV Streamer sticks (which can also be plugged into an LG for full control).

1. **On the TV**: Settings → System → About → click **Android TV OS build** 7 times. Then Settings → System → Developer options → **USB debugging ON**.
2. Find the TV's IP: Settings → Network & Internet → your Wi-Fi → **IP address**.
3. **On the PC** (same Wi-Fi as the TV), in the project folder:
   ```powershell
   powershell -ExecutionPolicy Bypass -File tools\build.ps1
   powershell -ExecutionPolicy Bypass -File tools\setup-tv.ps1 -Address <TV IP>
   ```
   Accept **"Allow USB debugging?"** on the TV (tick "Always allow").
4. The TV shows a **QR code**. On your phone: **Add TV → Google TV → Scan QR code on TV → Pair**.

If the TV's IP changes later (routers hand out new ones), find it again as in step 2 and use the new one with the tools scripts.
