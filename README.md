# Integra (Android)

WebView em tela cheia para https://integra.infra.gui.dev.br com Basic Auth, que funciona como tela inicial (launcher) e liga a tela sozinho.

## Build

```sh
export ANDROID_HOME=~/Android/sdk
./gradlew assembleRelease                      # app/build/outputs/apk/release/app-release.apk
./gradlew assembleDebug -PintegraUrl=https://… # outra URL (útil para testes)
```

No WSL, o celular USB só aparece pelo adb do Windows: `/mnt/c/platform-tools/adb.exe install -r <apk em C:\…>`.

## Release

Um push na `main` que muda o `versionName` em `app/build.gradle.kts` (com um `versionCode` maior) dispara o workflow **Release**:

1. compila o APK assinado (`assembleRelease`);
2. cria a tag `vX.Y` e uma Release no GitHub com notas geradas por IA (OpenCode + OpenRouter) e o APK anexado;
3. publica o APK no GitHub Packages como artefato OCI: `oras pull ghcr.io/gsbenevides2/integra-app:latest`.

O workflow também roda manualmente (*Actions → Release → Run workflow*): publica a versão atual se a tag dela ainda não existe.

O release é assinado com a chave de debug (a mesma das instalações via adb), guardada no secret `ANDROID_DEBUG_KEYSTORE_BASE64`, para o APK atualizar por cima do app instalado. Secrets do repo: `ANDROID_DEBUG_KEYSTORE_BASE64` e `OPENROUTER_API_KEY` (notas da release e review das PRs).

## Credenciais (Authentik)

O site fica atrás do proxy do Authentik, que aceita `Authorization: Basic` somente com **usuário + App password**. A senha normal do usuário não funciona.

1. Authentik → *Diretório → Usuários* → crie o usuário (ou use um existente). Se for só para o celular, pode ser um *service account*.
2. Garanta que ele tem acesso à aplicação do Integra (policy/grupo vinculado à aplicação).
3. Logado como esse usuário: *Configurações do usuário → Tokens e App passwords → Criar App password*. Como admin: *Diretório → Tokens e App passwords* → Criar, com *Intent* = **App password** e *User* = o usuário.
4. Teste: `curl -u 'usuario:APP_PASSWORD' -I https://integra.infra.gui.dev.br` deve responder 200, não 302.
5. No app, digite o usuário e o App password.

O Authentik **não cria sessão** a partir do Basic Auth, então o app coloca o header em todas as requisições:
- GETs para o integra são refeitos pelo `AuthInterceptor` (`shouldInterceptRequest`) com `Authorization` e cookies;
- `fetch`/XHR (POST etc.) recebem o header por um script injetado no início do documento (`installFetchAuthShim`);
- caminhos `/cdn-cgi/` (desafio da Cloudflare) ficam com o próprio WebView.
- `<video>` ao vivo (Frigate, fMP4/H.265): o player nativo não abre streams que passam pelo interceptador, então o `integra-shim.js` toca via Media Source Extensions quando o vídeo dá erro.

## Cloudflare

O User-Agent do app termina com `IntegraApp/<usuário>.<32 primeiros hex de sha256("usuário:app password")>`. Para calcular:

```sh
u=gsbenevides2; p='APP_PASSWORD'; echo "IntegraApp/$u.$(printf '%s:%s' "$u" "$p" | sha256sum | cut -c1-32)"
```

Crie uma regra *Skip* em Security → WAF → Custom rules com `http.host eq "integra.infra.gui.dev.br" and http.user_agent contains "IntegraApp/<valor>"`.
