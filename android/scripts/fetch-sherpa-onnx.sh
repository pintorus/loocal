#!/bin/sh
# Fetches the pinned sherpa-onnx Android library (Kokoro natural voices) into app/libs, hash-checked.
# It's a 38 MB binary, so it isn't committed. Run once after cloning: sh scripts/fetch-sherpa-onnx.sh
set -e
cd "$(dirname "$0")/.."
VERSION=2.0.1
FILE=sherpa-onnx-static-link-onnxruntime-$VERSION.aar
SHA256=07e042d97f3a6bd5b7fe74d4eef0513dc01a714b16c15a903250095aba43a575
mkdir -p app/libs
[ -f app/libs/$FILE ] || curl -L --fail -o app/libs/$FILE "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$VERSION/$FILE"
echo "$SHA256  app/libs/$FILE" | sha256sum -c -
