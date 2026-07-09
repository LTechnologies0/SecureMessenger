#!/usr/bin/env bash
# Configure Telegram api_id + api_hash in local.properties (one-time, for the APK builder).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROPS="$ROOT/local.properties"

echo "=== Configuration Telegram pour SecureMessenger ==="
echo ""
echo "L'utilisateur de l'app ne voit que : numéro → code SMS → (2FA)."
echo "Les identifiants ci-dessous sont pour le SERVEUR Telegram, comme dans"
echo "l'app officielle (BuildVars.APP_ID / APP_HASH), compilés dans l'APK."
echo ""
echo "Obtenez les vôtres sur https://my.telegram.org → API development tools"
echo ""

read -r -p "api_id (nombre, ex. 12345678): " API_ID
read -r -p "api_hash (texte, ex. abcdef0123456789...): " API_HASH

if [[ -z "$API_ID" || -z "$API_HASH" || "$API_ID" == "0" ]]; then
  echo "Erreur: api_id et api_hash requis." >&2
  exit 1
fi

touch "$PROPS"
# Remove old telegram lines if present
grep -v '^telegram\.api\.' "$PROPS" > "${PROPS}.tmp" 2>/dev/null || true
mv "${PROPS}.tmp" "$PROPS"

{
  echo ""
  echo "# Telegram — $(date -Iseconds 2>/dev/null || date)"
  echo "telegram.api.id=$API_ID"
  echo "telegram.api.hash=$API_HASH"
} >> "$PROPS"

echo ""
echo "OK — écrit dans $PROPS"
echo "Recompilez : cd $ROOT && ./gradlew :app:assembleDebug -x validateSigningDebug"
