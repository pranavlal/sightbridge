#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repository_root"

pandoc docs/PRIVACY_POLICY.md --from=gfm --to=html5 --standalone \
  --template=scripts/legal-template.html --metadata pagetitle='Privacy Policy' \
  --output=docs/privacy.html

pandoc docs/TERMS_OF_SERVICE.md --from=gfm --to=html5 --standalone \
  --template=scripts/legal-template.html --metadata pagetitle='Terms of Service' \
  --output=docs/terms.html
