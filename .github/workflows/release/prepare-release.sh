#!/usr/bin/env bash

set -euo pipefail

# =========================
# Lê a versão do app (versionName / versionCode)
# =========================
GRADLE_FILE="app/build.gradle.kts"

version_name() { sed -n 's/^ *versionName = "\(.*\)".*/\1/p' | head -1; }
version_code() { sed -n 's/^ *versionCode = \([0-9]*\).*/\1/p' | head -1; }

NEW_VERSION=$(version_name < "$GRADLE_FILE")
NEW_CODE=$(version_code < "$GRADLE_FILE")

if [ -z "$NEW_VERSION" ] || [ -z "$NEW_CODE" ]; then
    echo "versionName/versionCode não encontrados em $GRADLE_FILE"
    exit 1
fi

TAG="v${NEW_VERSION}"
PREV_TAG=$(git tag --sort=-version:refname | head -1)
echo "Versão: $NEW_VERSION (code $NEW_CODE) — tag anterior: ${PREV_TAG:-<nenhuma>}"

if git rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
    echo "A tag $TAG já existe: nada a publicar."
    echo "should_release=false" >> "$GITHUB_OUTPUT"
    exit 0
fi

# =========================
# Num push, só publica se a versão mudou
# =========================
if [ "${EVENT_NAME:-}" = "push" ]; then
    BASE="${BEFORE_SHA:-}"
    if [ -z "$BASE" ] || [ "$BASE" = "0000000000000000000000000000000000000000" ]; then
        BASE="HEAD~1"
    fi
    OLD_VERSION=$(git show "$BASE:$GRADLE_FILE" 2>/dev/null | version_name || echo "")
    OLD_CODE=$(git show "$BASE:$GRADLE_FILE" 2>/dev/null | version_code || echo "")

    if [ "$NEW_VERSION" = "$OLD_VERSION" ]; then
        echo "A versão não mudou ($NEW_VERSION): nada a publicar."
        echo "should_release=false" >> "$GITHUB_OUTPUT"
        exit 0
    fi
    # O Android só atualiza por cima com um versionCode maior.
    if [ -n "$OLD_CODE" ] && [ "$NEW_CODE" -le "$OLD_CODE" ]; then
        echo "versionName mudou ($OLD_VERSION → $NEW_VERSION), mas o versionCode não aumentou ($OLD_CODE → $NEW_CODE)"
        exit 1
    fi
    echo "Nova versão detectada: $OLD_VERSION → $NEW_VERSION"
fi

echo "should_release=true" >> "$GITHUB_OUTPUT"
echo "version=$NEW_VERSION" >> "$GITHUB_OUTPUT"
echo "prev_tag=$PREV_TAG" >> "$GITHUB_OUTPUT"
