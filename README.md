# Guitar Looper

Multi-track looper for Android (landscape, 9 circle pads).

## Build the APK with GitHub (no Android Studio needed)
1. Create a new GitHub repo and upload everything in this folder (keep the `.github` folder).
2. Go to the **Actions** tab -> "Build APK" runs automatically on push (or hit "Run workflow").
3. When it's green, open the run and download the **guitar-looper-debug** artifact. Unzip, copy `app-debug.apk` to your phone, install it (allow "install unknown apps").

## Using it
- Tap a circle: record. Tap again: stop. That defines the loop length.
- Tap another empty circle: it waits for the top of the loop, then records exactly one loop.
- Tap a filled circle: mute/unmute. Long-press: open the timing editor.
- Timing editor: drag the white line (start) or use the -100/-10/-1/+1/+10/+100 ms buttons. Each track keeps 2 s of audio from before you hit record and 2 s after, so you can pull the start earlier or the end later.
- Settings (gear): latency compensation for new recordings, input monitoring.
