#!/bin/bash
# package-cloud.sh — Build + package wuwei for cloud deployment (Linux CI/CD)
# Usage: ./scripts/package-cloud.sh
# Output: deploy/wuwei-cloud-{version}.tar.gz

set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION=$(grep "version =" "$ROOT/wuwei-core/build.gradle" | sed "s/.*'\(.*\)'.*/\1/")
OUT_DIR="$ROOT/deploy/wuwei-cloud-$VERSION"
ARCHIVE="$ROOT/deploy/wuwei-cloud-$VERSION.tar.gz"

echo "=== Wuwei Cloud Packaging v$VERSION ==="

# 1. Build frontend
echo "[1/4] Building frontend..."
cd "$ROOT/wuwei-renderer"
npm ci --silent
npm run build

# 2. Build kernel fat JAR
echo "[2/4] Building kernel fat JAR..."
cd "$ROOT/wuwei-core"
gradle fatJar --no-daemon

# 3. Assemble deploy directory
echo "[3/4] Assembling deploy package..."
rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR/dist"

cp "$ROOT/wuwei-core/build/libs/wuwei-kernel.jar" "$OUT_DIR/"
cp -r "$ROOT/wuwei-renderer/dist/"* "$OUT_DIR/dist/"

cat > "$OUT_DIR/wuwei.json" << EOF
{
  "version": "$VERSION",
  "llm": {
    "provider": "deepseek",
    "model": "deepseek-v4-pro",
    "apiKeyEnv": "WUWEI_API_KEY",
    "apiKey": ""
  },
  "logLevel": "info"
}
EOF

cat > "$OUT_DIR/start.sh" << 'STARTEOF'
#!/bin/bash
# wuwei cloud start
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
exec java -Xmx512m -jar "$SCRIPT_DIR/wuwei-kernel.jar" \
  --profile cloud \
  --web-root "$SCRIPT_DIR/dist" \
  --config "$SCRIPT_DIR/wuwei.json"
STARTEOF
chmod +x "$OUT_DIR/start.sh"

# 4. Package
echo "[4/4] Creating tar.gz..."
tar -czf "$ARCHIVE" -C "$ROOT/deploy" "wuwei-cloud-$VERSION"

echo "Done: $ARCHIVE"
echo "Deploy: tar -xzf wuwei-cloud-$VERSION.tar.gz && cd wuwei-cloud-$VERSION && WUWEI_API_KEY=sk-xxx ./start.sh"
