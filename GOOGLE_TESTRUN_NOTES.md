# Google Test Run notes

## What the passing v13 run (Testrun 2.3.0, TP1A.251121.014) actually showed
- Exactly one NTP packet from the tablet: NTPv4 (`0x23`) to `10.10.10.5:123`, ~5 s after the
  DHCP lease. That satisfied `ntp.network.ntp_support` (Required) and `ntp.network.ntp_dhcp`.
- The lab's DNS forwarder never got an upstream reply, so the tablet made no outbound TLS
  connections and every TLS client test was "Feature Not Detected" (passing).
- Testrun's DHCP offer carries no NTP option (42); `10.10.10.5` is hard-coded in Testrun's NTP
  module as "the DHCP provided NTP server".

## How Testrun grades these tests (Device Qualification pack)
- `ntp.network.ntp_support` (Required): Compliant if any NTPv4 packet from the device appears in
  startup.pcap, monitor.pcap or the NTP server's ntp.pcap. NTPv3 only = Non-Compliant; no NTP = Non-Compliant.
- `ntp.network.ntp_dhcp` (Roadmap, not in the verdict): Compliant if NTP goes only to 10.10.10.5;
  Non-Compliant if it goes to 10.10.10.5 and another server.
- `security.tls.*_client` (Required if Applicable): only Non-Compliant counts against the verdict.
  Non-Compliant is triggered by TLS 1.0/1.1 client hellos, hellos without ECDH/ECDSA ciphers, or
  non-TLS TCP/UDP traffic to public IPs (DNS, NTP, ICMP excluded) - including TCP SYNs that never
  complete a TLS handshake.

## Required communication behavior

### NTP path
- Protocol: NTPv4 / RFC 5905-compatible client request
- Transport: UDP
- Default destination: `10.10.10.5:123` (fallback `time.google.com` only if the primary has never answered on the current network)
- First packet byte: `0x23` (LI=0, VN=4, Mode=3)
- Sent at network-up, on IPv4 address change, then every 64 s

### TLS path
- Transport: HTTPS/TCP 443
- Minimum permitted TLS: 1.2
- Also permits TLS 1.3
- Cleartext HTTP: disabled
- Uses system trust anchors; no permissive/trust-all certificate manager is included.
- Only attempted on a network Android has validated for internet access

## Android 15 configuration
- `targetSdk 35`, `compileSdk 36`
- Foreground service type `specialUse` (not one of the types Android 15 blocks from BOOT_COMPLETED)
- Boot receiver handles LOCKED_BOOT_COMPLETED, BOOT_COMPLETED and MY_PACKAGE_REPLACED; direct-boot aware
- exported launcher activity declared explicitly, plus invisible `AutoStartActivity` for the kiosk
- Android 15 edge-to-edge system bar insets handled
- no hidden/non-SDK APIs

## Things outside this APK to check on the tablet
- The OS's own NTP client sends NTPv3. If DNS works in the lab it may query an external pool; that
  keeps `ntp_support` Compliant (v3 + v4) but marks the Roadmap `ntp_dhcp` test Non-Compliant.
- If the lab has internet, Android's plain-HTTP connectivity check (port 80) is non-TLS traffic to a
  public IP and can fail the TLS client tests regardless of this app. Check monitor.pcap.
- Private DNS set to a specific hostname causes DNS-over-TLS on port 853 to a public resolver.
- Wi-Fi should be off so the only external network is the Ethernet port Testrun is watching. On the
  MicroTouch IDC tablet the internal `usb0` link then becomes Android's default network; the app sends
  NTP by route (`NetworkSelector`), so its traffic still leaves on eth0.
- The Testrun device profile must use the tablet's eth0 MAC.
- Android 15 IDC firmware (AP3A.260207.015.A2) fails `connection.switch.arp_inspection`: with Testrun's
  30-second leases its network stack lets the IPv4 address expire at the moment it renews, so a late DHCP
  reply makes the tablet change address. This is a firmware issue (the Android 13 build passed); see
  `google_testrun_evidence/final_validation_report.md`.

## Important limitation
A normal third-party APK cannot set the Android system clock without privileged/system-level authorization. This app measures NTP response/version, offset, and delay. If the Test Run requires the device OS itself to consume NTPv4 for automatic time, that change belongs in the Android platform/device configuration rather than only in this APK.
