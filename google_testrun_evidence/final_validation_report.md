# Final Validation Report - Google Testrun (Android 15 Boot-Start)

**Device:** MicroTouch IDC_Series (TES), MediaTek MT8188
**Android:** 15 (API 35), firmware V3.0, build AP3A.260803.015.A2 (earlier runs: build AP3A.260207.015.A2, user/release-keys)
**Application:** 4.0-android15-autostart (versionCode 4)
**APK SHA-256 (debug build tested):** 2F056F93936381143B6EF6935F98017C3E6C36B6F2C32E85E07358FF1E3537E9

## FINAL STATUS: COMPLIANT IN GOOGLE TESTRUN ON FIRMWARE V3.0

On firmware V3.0 the tablet passed Google Testrun 2.4.0 (Device Qualification) in three full runs
on 2026-10-09. All 31 Required tests were Compliant in each run, including
`connection.switch.arp_inspection`. The app produced NTPv4 traffic to Testrun's NTP server
automatically at boot, with no manual steps.

| Run (2026-10-09) | Overall | Required tests | ntp_dhcp (Roadmap, not in the verdict) |
| --- | --- | --- | --- |
| 11:09-11:26 | Compliant | 31/31 Compliant | Non-Compliant: the Testrun host was offline during its NTP trust check |
| 11:39-11:54 | Compliant | 31/31 Compliant | Compliant |
| 12:13-12:28 | Compliant | 31/31 Compliant | Compliant |

On the earlier build (AP3A.260207.015.A2, 2026-10-08) the app's tests passed the same way, but the
overall result was Non-Compliant because of `connection.switch.arp_inspection`, caused by the
firmware's DHCP handling. See "Known device issue" below.

## TESTRUN RESULTS RELEVANT TO THIS APP (same on both builds)

| Test | Required? | Result |
| --- | --- | --- |
| ntp.network.ntp_support | Required | Compliant - device sent NTPv4 (plus NTPv3 from the OS) |
| ntp.network.ntp_dhcp | Roadmap | Compliant |
| security.tls.v1_0_client / v1_2_client | Required if Applicable | Feature Not Detected (passing) |
| security.tls.v1_3_client | Informational | TLS 1.3 client connections valid |
| dns.network.hostname_resolution | Required | Compliant |

Packet capture of Testrun's device port: the first NTPv4 request (byte 0 = 0x23) reached
10.10.10.5:123 about 1 s after the tablet's DHCP lease; Testrun's server answered 0xE4
(LI=3, VN=4, stratum 0), reported by the app as PASS / "server unsynchronized".

## HANDS-FREE BOOT (adb reboot, no interaction)

| Seconds since boot | Event |
| --- | --- |
| 21.0 | `LOCKED_BOOT_COMPLETED received` -> foreground service started |
| 21.1 | First NTPv4 request (0x23) |
| 22.4 | `BOOT_COMPLETED received` (service already running) |
| 30.4 | TLS probe on a validated network: TLSv1.3, HTTP 204 (office network only) |

`dumpsys activity services` after reboot: `isForeground=true types=0x40000000` (specialUse).

## NTPv4
* Request first byte 0x23 (LI=0, VN=4, Mode=3), UDP 123, default destination 10.10.10.5
* Sent on the network that has a route to the server (`NetworkSelector`), not Android's default
  network. The IDC tablet's internal `usb0` link (192.168.63.0/24, no gateway) is also typed
  Ethernet and becomes the default network when Wi-Fi is off; route selection keeps NTP on eth0.
* Testrun-style reply (LI=3, stratum 0) verified against an emulated server: PASS, origin timestamp checked

## TLS 1.2+
* TLS 1.0/1.1 disabled; TLS 1.2 and 1.3 enabled; negotiated protocol recorded from the SSL session
* Only attempted on networks Android has validated for internet access

## BUILD
* `gradlew :app:assembleDebug` - BUILD SUCCESSFUL; `:app:lintDebug` - 0 errors
* Packaged manifest verified with aapt2: boot actions, directBootAware receiver and service,
  foregroundServiceType=specialUse (0x40000000)

## DEVICE SETUP FOR TESTRUN
* Wi-Fi off, so the only external network is the Ethernet port Testrun watches
* Testrun device profile MAC = the tablet's eth0 MAC (not a randomized address)
* Lock screen disabled on the test device (BOOT_COMPLETED follows immediately; LOCKED_BOOT_COMPLETED covers locked devices)

## KNOWN DEVICE ISSUE (FIRMWARE, NOT THIS APP)
`connection.switch.arp_inspection` failed on the earlier Android 15 build (AP3A.260207.015.A2).
Testrun issues 30-second DHCP leases; the firmware's network stack gives the IPv4 address a 30 s
lifetime but renews only at ~30 s, so a renewal answered ~1 s late loses the address, Ethernet
restarts and the tablet takes a different IP. ARP sent from the other address is graded as false.
The Android 13 firmware kept its address through the same delays and passed. A firmware fix has been
requested from the vendor.

Firmware V3.0 passed this test in all three runs, but its network stack still gives the address a
30 s lifetime. The pass relied on Testrun's DHCP server answering every renewal promptly, so the
request to the vendor stands.
