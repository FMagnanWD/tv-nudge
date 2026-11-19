#!/bin/bash
# Sends a message to TV Nudge on the Fire TV and waits until it is seen.
#   FIRE_TV=192.168.1.20 ./tools/send.sh "Dinner's ready"
#   FIRE_TV=192.168.1.20 ./tools/send.sh "Shower time!" "Dad"     (optional title, "Message" by default)
set -e
[ -z "$FIRE_TV" ] && { echo "Set FIRE_TV to the Fire TV's IP address"; exit 1; }
[ -z "$1" ] && { echo "Usage: FIRE_TV=<ip> $0 \"text\" [title]"; exit 1; }

exec python3 -I - "$FIRE_TV" "$1" "${2:-Message}" <<'PY'
import json, sys, time, urllib.request

base, text, title = "http://" + sys.argv[1] + ":7979", sys.argv[2], sys.argv[3]
try:
    body = json.dumps({"texte": text, "titre": title}).encode()
    request = urllib.request.Request(base + "/message", body, {"Content-Type": "application/json"})
    ident = json.load(urllib.request.urlopen(request, timeout=5))["id"]
except Exception as e:
    sys.exit(f"The Fire TV does not answer (off?): {e}")

print("Message shown, waiting…")
while True:
    time.sleep(2)
    state = json.load(urllib.request.urlopen(f"{base}/etat?id={ident}", timeout=5))["etat"]
    if state != "affiché":
        print({"vu": "✅ Seen (OK)", "fermé": "Closed with Back", "expiré": "⏱ Not seen: gone after one minute",
               "remplacé": "Replaced by another message"}.get(state, state))
        break
PY
