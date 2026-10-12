#!/usr/bin/env bash

set -euo pipefail

# =========================
# Chave de assinatura
# =========================
# O release é assinado com a chave de debug local (app/build.gradle.kts), a mesma
# das instalações via adb, para o APK da CI atualizar por cima do app instalado.
# O keystore vai para um caminho explícito (INTEGRA_KEYSTORE): em ~/.android o AGP
# não o achava e gerava uma chave nova a cada build.
EXPECTED_CERT_SHA256="c1d21828850a6a8e479eac17e02c98a8073988c7ffd6a264f71d254d53dbf89b"

if [ -z "${ANDROID_DEBUG_KEYSTORE_BASE64:-}" ]; then
    echo "Secret ANDROID_DEBUG_KEYSTORE_BASE64 ausente"
    exit 1
fi

export INTEGRA_KEYSTORE="${RUNNER_TEMP:-/tmp}/integra-debug.keystore"
echo "$ANDROID_DEBUG_KEYSTORE_BASE64" | base64 -d > "$INTEGRA_KEYSTORE"
keytool -list -keystore "$INTEGRA_KEYSTORE" -storepass android -alias androiddebugkey > /dev/null

# =========================
# Build
# =========================
echo "Construindo o APK v${VERSION}..."

./gradlew --no-daemon assembleRelease

APK=app/build/outputs/apk/release/app-release.apk

# Um APK com outra chave não instala por cima: falha aqui em vez de publicar.
APKSIGNER="$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)/apksigner"
CERT_SHA256="$("$APKSIGNER" verify --print-certs "$APK" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')"
if [ "$CERT_SHA256" != "$EXPECTED_CERT_SHA256" ]; then
    echo "APK assinado com o certificado errado: $CERT_SHA256 (esperado $EXPECTED_CERT_SHA256)"
    exit 1
fi

mkdir -p dist
cp "$APK" "dist/integra-v${VERSION}.apk"

echo "APK pronto: dist/integra-v${VERSION}.apk"
