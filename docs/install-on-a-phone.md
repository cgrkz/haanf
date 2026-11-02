# Install Haanf on a friend's phone

The debug APK is at `app/build/outputs/apk/debug/app-debug.apk`. To rebuild it:

```bash
./gradlew :app:assembleDebug
```

## Send the file

Send `app-debug.apk` by Quick Share, a cable or a private file link. Build output is not in git.

Later updates install over the top only if they are built on the same Mac, because debug builds are signed with that machine's debug key. An APK from another machine means uninstalling first, which deletes Haanf's data on that phone.

## On the friend's phone

1. Open the APK from Files or the share notification.
2. If asked, allow installs from that app: **Settings > Apps > Special app access > Install unknown apps**. Wording varies by manufacturer.
3. If Play Protect warns about an unknown app, tap **More details > Install anyway**.
4. Open Haanf and allow Bluetooth, the microphone and notifications when asked.
5. On Android 13 or older, Health Connect is a separate app. The Health data tab links to it in the Play Store.

## Install with a cable or wireless debugging

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Load a key for testing

Friends type their own key into the Coach tab. To load the Hinoki key from `.env` onto a connected phone without building it into the APK, run this from the repo root. It writes a file into Haanf's private storage, and Haanf encrypts the key and deletes the file on its next launch.

```bash
python3 -c "import re;k=[re.match(r'LLM_KEY=(.*)',l).group(1).strip().strip('\"\'') for l in open('.env') if l.startswith('LLM_KEY=')][0];print('HINOKI\nhinoki-offensive\n'+k)" | adb shell "run-as app.haanf sh -c 'cat > files/dev_ai_key'"
adb shell am force-stop app.haanf
adb shell am start -n app.haanf/.MainActivity
```

The key is never in the command line or the APK. When Hinoki is selected, your real profile, health and workout data goes to it.

## Phones that kill background apps

Some manufacturers stop foreground services to save battery. If a screen-off test stops collecting samples, set Haanf to unrestricted battery use in **Settings > Apps > Haanf > Battery**, then repeat the test. A session ended by Android shows `app_process_ended` under "Ended by".

## Remove it

Uninstall Haanf normally. That deletes all sessions and recordings, because they exist only in its private storage.
