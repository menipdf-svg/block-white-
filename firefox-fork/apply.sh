#!/usr/bin/env bash
# מחיל את השינויים שלנו על קוד הפיירפוקס. שלב 1: בלי שינויים — רק לוודא שהבנייה עובדת.
set -euo pipefail
FF="$1"
echo "Applying changes to $FF"
