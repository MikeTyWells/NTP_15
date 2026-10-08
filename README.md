# NTP Client - Android 15 / Google Test Run diagnostic build

Version 4.0 (`4.0-android15-autostart`). Generates the NTPv4 and TLS 1.2+ traffic that Google
Testrun grades, automatically at boot, with no manual steps on the tablet.

## Validation goals
- Android 15 compatibility: `targetSdk 35` (`compileSdk 36`).
- NTPv4 request: client request header explicitly uses NTP version 4 (`VN=4`, client mode 3, first byte `0x23`).
- Default NTP server: `10.10.10.5`, UDP port 123 - Testrun's NTP server, the same destination the
  passing v13 run used. A literal IP needs no DNS, so it works on an isolated test network.
- TLS communications baseline: HTTPS path permits TLS 1.2 and TLS 1.3 only.
- Cleartext HTTP disabled with Android Network Security Config.
- No privileged clock-setting permissions; this is a communications/test utility, not a system clock setter.

## How it starts with no manual steps
`NetworkValidationService` is a foreground service (type `specialUse`) started by:

| Trigger | When |
| --- | --- |
| `LOCKED_BOOT_COMPLETED` | Boot, before unlock (receiver and service are direct-boot aware) |
| `BOOT_COMPLETED` | Boot, after unlock (immediately on tablets without a lock screen) |
| `MY_PACKAGE_REPLACED` | After `adb install -r` of a new version |
| `MainActivity` / `AutoStartActivity` | Whenever the kiosk (or a person) launches the app |

Android does not deliver boot broadcasts to a freshly installed app until it has been launched once.
Having the kiosk launch `AutoStartActivity` (invisible; starts the service and finishes) at boot
covers that first boot as well.

Once running, the service:
- sends NTPv4 to the configured server as soon as any network is available, on every IPv4
  address change, then every 64 s (every 1024 s after 30 minutes without network changes);
- sends each request on the network that has a route to the server (`NetworkSelector`), not on
  Android's default network. The IDC tablet's internal `usb0` link (192.168.63.0/24, no gateway) is
  also typed Ethernet and can be the default on an isolated lab; route-based selection keeps NTP for
  10.10.10.5 on `eth0`;
- uses the fallback server (`time.google.com`) only while the primary has not answered since the
  last network change, so a lab whose server answers sees traffic to that server only;
- runs the TLS 1.2+ HTTPS probe once per network (and hourly) only after Android marks the network
  as having validated internet access, so an isolated lab never sees half-open TCP connections to
  public addresses;
- holds a partial wake lock so polling continues while the screen is off (kiosk is mains-powered).

Results are stored in device-protected storage and shown live in the app.

## Configuration (no UI needed)
Pass extras to either activity, e.g. from the kiosk app, an MDM, or adb:

    adb shell am start -n com.example.ntp/.AutoStartActivity --es ntp_server 10.10.10.5
    adb shell am start -n com.example.ntp/.AutoStartActivity --es ntp_fallback ""      # disable fallback
    adb shell am start -n com.example.ntp/.AutoStartActivity --es tls_url https://www.google.com/generate_204

The **Use This Server for Automatic Validation** button in the app does the same for the NTP server.

## Build
Open this folder in Android Studio (JDK 21 / Android Studio JBR), or:

    gradlew.bat :app:assembleDebug

Output: `app/build/outputs/apk/debug/app-debug.apk`

## Install and verify

    adb install -r app-debug.apk
    adb shell am start -n com.example.ntp/.AutoStartActivity
    adb reboot
    adb logcat -s NTPValidation

Expect, after the reboot: `LOCKED_BOOT_COMPLETED received` / `BOOT_COMPLETED received`,
`Network available`, `Boot NTP Test (network available): 10.10.10.5:123 (NTPv4 request byte 0x23)`,
then `Boot NTP PASS: v4 from 10.10.10.5 ...`. On Testrun's network the server reports itself
unsynchronized (LI=3, stratum 0) because it has no upstream; the exchange still passes.

For packet capture, verify that the NTP client request byte 0 is `0x23`, indicating LI=0, VN=4, Mode=3.
