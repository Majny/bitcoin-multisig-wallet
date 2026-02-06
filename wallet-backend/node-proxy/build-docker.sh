#!/bin/bash
# Build a push node-proxy image pro Raspberry Pi

IMAGE_NAME="node-proxy"
TAG="${1:-latest}"

# Detekce platformy - pro cross-compile na ARM64
PLATFORM="${PLATFORM:-linux/arm64}"

echo "🔨 Building $IMAGE_NAME:$TAG for $PLATFORM..."

# Build s buildx pro ARM64
docker buildx build \
    --platform "$PLATFORM" \
    -t "$IMAGE_NAME:$TAG" \
    --load \
    .

echo "✅ Image built: $IMAGE_NAME:$TAG"
echo ""
echo "📦 Pro push na RPi můžeš použít:"
echo "   docker save $IMAGE_NAME:$TAG | ssh majny@100.79.139.14 'docker load'"
echo ""
echo "🚀 Na RPi spusť pomocí:"
echo "   docker run -d --name node-proxy \\"
echo "     --network host \\"
echo "     -e RPC_URL=http://127.0.0.1:8332/ \\"
echo "     -e RPC_USER=backend \\"
echo "     -e RPC_PASS=<heslo> \\"
echo "     -e API_KEY=<api-key> \\"
echo "     $IMAGE_NAME:$TAG"
