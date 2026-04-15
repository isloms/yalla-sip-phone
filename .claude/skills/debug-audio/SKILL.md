---
name: debug-audio
description: Debug audio routing issues in yalla-sip-phone — PJSIP media pipeline, codec negotiation, Asterisk SDP, conference bridge routing, sound device selection. Use when audio is broken or silent on calls. Triggers include phrases like "audio broken", "no audio", "one-way audio", "debug audio", "audio issue", "can't hear", "silent call".
allowed-tools: Read, Bash, Glob, Grep
---

# Debug Audio

Audio issues in SIP softphones have a large surface: codec negotiation, NAT, SDP, media transport, sound device, conference bridge, jitter buffer. This skill walks through the common causes in order of likelihood.

## Known Ongoing Issue

**Asterisk NAT misconfiguration** is the CURRENT blocker. Asterisk SDP advertises `87.237.239.18` (public IP) instead of `192.168.30.103` (LAN). Fix: Asterisk admin must add `localnet=192.168.30.0/24` to pjsip config. **Our client code is correct** — do not "fix" it.

If the user's symptom matches "RTP is sent but not received" or "one-way audio with remote server IP", this is the issue. Tell Islom and stop.

## Diagnostic Flow

### 1. Determine Symptom

Ask the user or infer from logs:
- **Silent on both ends**: likely codec mismatch or mic/speaker not selected
- **One-way audio (I can hear them, they can't hear me)**: local mic issue or NAT (we→them RTP blocked)
- **One-way audio (they can hear me, I can't hear them)**: NAT (them→us RTP blocked) or speaker issue
- **Choppy / garbled audio**: jitter buffer or packet loss or wrong codec
- **Audio starts then cuts off**: SDP re-negotiation or session timer issue

### 2. Check Sound Device

```kotlin
// In SipModule or SoundDeviceManager
// Verify:
// - Mic device is not muted at OS level
// - Speaker device is not using 0% volume
// - Sound device is actually captured by PJSIP (check logs for "snd_port")
```

Inspect `PjsipEngine` logs at startup — PJSIP enumerates devices and prints them. If the expected device isn't in the list, OS permissions or driver issue.

### 3. Check SDP

Enable PJSIP log level 5 (`PJ_LOG` level 5 during development) and capture the SIP INVITE/200 OK exchange. Look at the SDP body:

```
v=0
o=- 3871254 3871254 IN IP4 <address>         ← This address matters
...
c=IN IP4 <address>                            ← And this one
m=audio 4000 RTP/AVP 8 0 101
```

Both addresses should be the LAN IP (`192.168.x.x`), not a public IP. If they're public, it's the Asterisk NAT issue (see "Known Ongoing Issue").

### 4. Check Codec Negotiation

```
m=audio 4000 RTP/AVP 8 0 101
```

- `8` = PCMA (G.711 A-law)
- `0` = PCMU (G.711 µ-law)
- `101` = telephone-event (DTMF)

If the SDP shows only exotic codecs (9 = G.722, 18 = G.729), and PJSIP wasn't built with those codecs, negotiation fails. Check `PJMEDIA_HAS_*` defines in `pjproject/pjlib/include/pj/config_site.h`.

### 5. Check Conference Bridge Routing

`PjsipEngine` uses a conference bridge to connect calls to sound devices. Verify:
- Call is connected to the bridge (look for `pjmedia_conf_connect_port` in logs)
- Sound device is connected to the bridge
- Conference bridge is running at a sensible clock rate

### 6. Run PJSIP's Diagnostic Tools

```bash
# If pjsip is built in ~/Ildam/pjproject/
./pjsip-apps/bin/pjsua --null-audio --log-level=5 \
  --registrar sip:192.168.30.103 --id sip:101@192.168.30.103 \
  --username 101 --password <pwd>
```

If PJSIP's own reference app hits the same issue, it's not our wrapper — it's PJSIP config or server side.

### 7. Check Jitter Buffer

Look for log entries containing "jbuf" or "jitter". High packet loss (>5%) or large jitter (>100ms) will garble audio. Usually network, not code.

### 8. Check Sample Rate Mismatch

PJSIP's default is 8kHz (telephony). If you configured a wider codec (G.722 at 16kHz) but the conference bridge is at 8kHz, audio will be wrong speed or silent. Check `ConfPort` sample rate matches codec.

## Tools

- Wireshark on port 5060 (SIP) and dynamic RTP ports: capture SDP and packet flow
- `tcpdump -i any port 5060` for SIP-only
- PJSIP log level 5 for verbose output
- Our own logs under `data/log/yalla-sip-phone.log`

## What to Touch vs Not

- **Touch**: sound device selection, codec priority, conference bridge wiring, log level
- **Do NOT touch**: the pjsip C source code in `~/Ildam/pjproject/` — modifications make us incompatible with upstream
- **Do NOT touch**: the core pjDispatcher threading — see `rules/pjsip-threading.md`

## Reference

- `docs/pjsip-guide.md` — our PJSIP integration guide
- `pjproject/pjlib/include/pj/config_site.h` — compile-time codec config
- PJSIP docs: https://docs.pjsip.org/en/latest/
