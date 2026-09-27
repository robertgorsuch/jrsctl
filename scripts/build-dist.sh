#!/usr/bin/env bash
# Builds the portable distribution for this platform: jlink image, tar.gz (zip on Windows), checksum, SBOM.
# Output: dist/target/image/<platform>/ and dist/target/jrsctl-<version>-<platform>.tar.gz
# Usage: scripts/build-dist.sh [extra maven args]
set -euo pipefail
exec bash "$(dirname "$0")/mvn.sh" -Pdist -DskipTests package "$@"
