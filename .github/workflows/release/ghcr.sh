#!/usr/bin/env bash

set -euo pipefail

# =========================
# Publica o APK no GitHub Packages (ghcr.io) como artefato OCI
# =========================
# Baixar: oras pull ghcr.io/<owner>/<repo>:<versão|latest>

REGISTRY="ghcr.io"
REPO_OWNER="${FULL_REPO%%/*}"
IMAGE_URL="${REGISTRY}/${FULL_REPO,,}"
MINOR_VERSION=$(echo "$VERSION" | cut -d. -f1-2)
MAJOR_VERSION=$(echo "$VERSION" | cut -d. -f1)
APK_TYPE="application/vnd.android.package-archive"
APK="integra-v${VERSION}.apk"

echo "Logando no Github Container Registry..."

echo "${TOKEN_GITHUB}" | oras login "${REGISTRY}" -u "${REPO_OWNER}" --password-stdin

echo "Publicando ${APK} em ${IMAGE_URL}:${VERSION}..."

cd dist
oras push "${IMAGE_URL}:${VERSION}" \
    --artifact-type "$APK_TYPE" \
    --annotation "org.opencontainers.image.source=https://github.com/${FULL_REPO}" \
    --annotation "org.opencontainers.image.version=${VERSION}" \
    --annotation "org.opencontainers.image.description=APK do app Android do Integra" \
    "${APK}:${APK_TYPE}"

# Com versão "X.Y", a tag minor é a própria versão: não repete.
EXTRA_TAGS=$(printf '%s\n' latest "$MINOR_VERSION" "$MAJOR_VERSION" | grep -vxF "$VERSION" | sort -u)
# shellcheck disable=SC2086
oras tag "${IMAGE_URL}:${VERSION}" $EXTRA_TAGS

echo "APK publicado com tags: ${VERSION} $(echo $EXTRA_TAGS)"
