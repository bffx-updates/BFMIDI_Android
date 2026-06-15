# BFMIDI Editor — app Android (wrapper WebView)

App Android mínimo que abre o **editor do pedal BFMIDI** em tela cheia, sem a
barra do navegador. Resolve a impossibilidade de instalar o editor como PWA no
Android (o pedal serve em HTTP local, e o Chrome só instala PWA em HTTPS).

Ao abrir, o app procura o pedal em `http://192.168.4.1` (modo AP) e, se não
achar, em `http://bfmidi.local` (mDNS, modo STA). O primeiro que responder é
carregado. Se nenhum responder, mostra uma tela de "Tentar de novo".

Suporta o upload de imagens/ícones do editor (abre o seletor de arquivos do
Android). Não usa Web Serial (USB) — só Wi-Fi.

## Como gerar o APK (build na nuvem)

Não precisa instalar nada localmente. O build roda no **GitHub Actions**:

1. Crie um repositório no GitHub (ex.: `bffx-updates/BFMIDI_Android`).
2. Suba **o conteúdo desta pasta** (`android_app/`) na **raiz** do repositório.
3. O workflow [`.github/workflows/build-apk.yml`](.github/workflows/build-apk.yml)
   roda sozinho no push (ou manualmente em **Actions → Build APK → Run workflow**).
4. Ao terminar, baixe o `BFMIDI-editor.apk`:
   - na aba **Releases** (publicado a cada build), ou
   - em **Actions → run → Artifacts**.

## Como instalar no celular

1. Baixe o `BFMIDI-editor.apk` no Android.
2. Abra o arquivo; o Android vai pedir pra permitir **"instalar apps de fontes
   desconhecidas"** pro navegador/gerenciador de arquivos. Permita uma vez.
3. Instale. O ícone do BFMIDI aparece na gaveta de apps.
4. Conecte ao Wi-Fi do pedal (ou à rede onde ele está) e abra o app.

## Build local (opcional, Android Studio)

Abra a pasta `android_app/` no Android Studio. Ele baixa o SDK/Gradle e gera o
APK em **Build → Build APK(s)**. Versão mínima: Android 7 (API 24).
