# BFMIDI Editor — app Android (wrapper WebView)

App Android que roda o **editor BFMIDI** em tela cheia, sem a barra do navegador.

**A UI do editor é EMBUTIDA no próprio APK** (`app/src/main/assets/`, build do
`webApp/`), não vem mais do pedal. O app só fala com o pedal pela **API JSON**
(HTTP local) usando o `?api=` do editor. Vantagens: mudança de tela = atualizar
o APK (sem reflashar a LittleFS), carrega mais rápido e sem cache velho do PWA.

Ao abrir, sonda o pedal em `http://192.168.4.1` (AP) e `http://bfmidi.local`
(mDNS, STA): se algum responder, passa o endereço no `?api=`; se nenhum responder,
carrega a UI local mesmo assim e usa o IP que o usuário fixou (a própria tela de
conexão do editor permite trocar/fixar o IP em runtime).

Suporta upload de imagens/ícones (seletor de arquivos do Android). Só Wi-Fi
(sem Web Serial/USB).

### Atualizar a UI embutida (snapshot)

A pasta `app/src/main/assets/` é a UI do editor **buildada** (`BF_NO_GZIP=1
npm run build` no `webApp/`, copiada do `data/` resultante — arquivos
DESCOMPACTADOS, pois `file://` não decodifica `.gz`). O firmware (privado, local)
**não** entra aqui: só o editor compilado, que já é público em qualquer pedal.
Ao mudar a UI: rebuildar → copiar `data/` → `assets/` → commit/push → o CI gera
o APK novo.

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

## Atualizar o app no celular

O CI assina todo APK com uma **chave fixa** (`app/bfmidi-release.jks`, gerada
e commitada automaticamente na 1ª execução) e sobe o `versionCode` a cada build.
Por isso, atualizar é só: baixar o `BFMIDI-editor.apk` mais novo, abrir e tocar
em **"Atualizar"** — sem desinstalar, mantendo tudo.

> Exceção única: ao migrar dos builds antigos (build-1/build-2, que usavam chave
> de debug variável) para o primeiro build com a chave fixa, é preciso
> **desinstalar uma última vez** — depois disso as atualizações são um toque só.

## Build local (opcional, Android Studio)

Abra a pasta `android_app/` no Android Studio. Ele baixa o SDK/Gradle e gera o
APK em **Build → Build APK(s)**. Versão mínima: Android 7 (API 24).
