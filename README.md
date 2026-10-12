# Integra (Android)

WebView em tela cheia para https://integra.infra.gui.dev.br com Basic Auth, que funciona como tela inicial (launcher) e liga a tela sozinho.

## Build

```sh
export ANDROID_HOME=~/Android/sdk
./gradlew assembleRelease                      # app/build/outputs/apk/release/app-release.apk
./gradlew assembleDebug -PintegraUrl=https://… # outra URL (útil para testes)
```

No WSL, o celular USB só aparece pelo adb do Windows: `/mnt/c/platform-tools/adb.exe install -r <apk em C:\…>`.

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
