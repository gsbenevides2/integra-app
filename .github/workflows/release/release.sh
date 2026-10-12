#!/usr/bin/env bash

set -euo pipefail

TAG="v${VERSION}"
APK="dist/integra-v${VERSION}.apk"

# =========================
# Monta contexto para IA
# =========================
echo "Montando contexto para geração de release notes..."

{
    echo "Versão: ${TAG}"
    echo

    echo "=== COMMITS ==="

    if [ -z "$PREV_TAG" ]; then
        git log --format="- %s (%h by %an)"
    else
        git log "${PREV_TAG}..HEAD" --format="- %s (%h by %an)"
    fi

    echo
    echo "=== DIFF (sem o wrapper do Gradle) ==="

    if [ -z "$PREV_TAG" ]; then
        git diff HEAD~5..HEAD -- . ':!gradle/wrapper' ':!gradlew' ':!gradlew.bat' 2>/dev/null > /tmp/full_diff.txt || true
    else
        git diff "${PREV_TAG}..HEAD" -- . ':!gradle/wrapper' ':!gradlew' ':!gradlew.bat' 2>/dev/null > /tmp/full_diff.txt || true
    fi
    head -c 40000 /tmp/full_diff.txt || true
} > /tmp/changes.txt
echo "Contexto montado em /tmp/changes.txt"

# =========================
# Instala OpenCode
# =========================
echo "Instalando OpenCode CLI..."

npm install -g opencode-ai@1.15.13

echo "OpenCode CLI instalado: $(opencode --version)"

# =========================
# Gera release notes
# =========================
echo "Gerando release notes com IA..."

cat > /tmp/prompt.txt << 'PROMPT'
Você é um assistente especializado em criar release notes de software.

Analise os commits e o diff abaixo do app Android do Integra (Kotlin/Compose)
e gere release notes em português para a versão indicada.

Organize em categorias com emojis:
- 🚀 Novidades
- 🐛 Correções
- ⚡ Melhorias
- 🔧 Mudanças internas

Use markdown com listas.

Seja informativo e conciso.

Ignore menções ao wrapper do Gradle.

PROMPT

cat /tmp/changes.txt >> /tmp/prompt.txt

GENERATED=$(opencode run --model openrouter/xiaomi/mimo-v2.5 "$(cat /tmp/prompt.txt)" 2>/dev/null || true)

echo "Release notes geradas (ou fallback):"
echo "$GENERATED"

if [ -z "$GENERATED" ]; then
    echo "Nenhuma release note gerada, usando fallback."
    GENERATED="Release automático - veja os commits para detalhes."
fi

{
    echo "$GENERATED"
    echo
    echo "---"
    echo
    echo "📦 Instalar: baixe \`integra-v${VERSION}.apk\` abaixo, ou \`adb install -r integra-v${VERSION}.apk\`."
    echo "Também no GitHub Packages: \`oras pull ghcr.io/${FULL_REPO}:${VERSION}\`."
} > /tmp/release_notes.md

# =========================
# Cria a tag e a Release no GitHub, com o APK
# =========================
echo "Criando release ${TAG} no GitHub..."

gh release create "$TAG" "$APK" \
    --repo "$FULL_REPO" \
    --target "$GITHUB_SHA" \
    --title "$TAG" \
    --notes-file /tmp/release_notes.md \
    --latest

echo "Release ${TAG} criada com sucesso no GitHub."
