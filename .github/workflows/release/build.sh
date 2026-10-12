#!/usr/bin/env bash

set -euo pipefail

# =========================
# Chave de assinatura
# =========================
# O release é assinado com a chave de debug (app/build.gradle.kts), a mesma das
# instalações via adb, para o APK da CI atualizar por cima do app instalado.
# Sem ela o Gradle geraria uma chave nova e o APK não instalaria por cima.
if [ -z "${ANDROID_DEBUG_KEYSTORE_BASE64:-}" ]; then
    echo "Secret ANDROID_DEBUG_KEYSTORE_BASE64 ausente"
    exit 1
fi

mkdir -p ~/.android
echo "$ANDROID_DEBUG_KEYSTORE_BASE64" | base64 -d > ~/.android/debug.keystore

# =========================
# Build
# =========================
echo "Construindo o APK v${VERSION}..."

./gradlew --no-daemon assembleRelease

mkdir -p dist
cp app/build/outputs/apk/release/app-release.apk "dist/integra-v${VERSION}.apk"

echo "APK pronto: dist/integra-v${VERSION}.apk"
