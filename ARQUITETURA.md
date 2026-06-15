# BFMIDI Android — Arquitetura & Processo

Documento de referência de **como o app Android é construído, assinado, publicado
e atualizado**. Companion do [README.md](README.md) (que é o "comece por aqui").

---

## 1. Por que este app existe

O editor do BFMIDI é uma **web app (PWA)** servida pelo próprio pedal por **HTTP
local** (`http://192.168.4.1` no AP, ou o IP/`bfmidi.local` no STA).

No **iOS** dá pra "Adicionar à Tela de Início" e abrir em tela cheia. No **Android**
**não**: o Chrome só instala PWA em **HTTPS** (ou `localhost`), e o pedal é HTTP.
O service worker nem registra em HTTP → o botão "Instalar app" nunca aparece.

Tentar resolver com **HTTPS self-signed no firmware não compensa**: certificado não
confiável bloqueia o service worker do mesmo jeito (continuaria sem instalar), além
de custar ~22–42 KB de RAM por conexão TLS no ESP32-S2 e um rewrite do servidor.

**Solução:** um app Android nativo mínimo que renderiza a UI num **WebView**. O
WebView ignora as regras de contexto seguro/PWA — só desenha a tela em fullscreen.

---

## 2. Arquitetura

### 2.1. UI embutida (não vem do pedal)

> Esta é a decisão central. Mudou em jun/2026 (antes a UI vinha do pedal).

A tela do editor vive **dentro do APK**, em `app/src/main/assets/`. O WebView
carrega de `file:///android_asset/index.html`. O app fala com o pedal **apenas
pela API JSON** (HTTP): `/bank/current`, `/config/global`, `/sw/params`, etc.

```
┌─────────────────────────── CELULAR (APK) ───────────────────────────┐
│  WebView                                                            │
│    file:///android_asset/index.html   ← UI (assets do APK)         │
│    app.js / app.css / icons                                        │
│            │                                                       │
│            │  fetch HTTP (?api=http://<pedal>)                      │
└────────────┼──────────────────────────────────────────────────────┘
             │  Wi-Fi (LAN)
             ▼
┌──────────────────── PEDAL (ESP32-S2) ───────────────────────────────┐
│  WebServer :80  →  só a API JSON é usada pelo app                   │
│  (continua servindo a UI também, pra quem usa pelo navegador)       │
└─────────────────────────────────────────────────────────────────────┘
```

**Vantagens:**
- **Mudança de tela = atualizar o APK** (não precisa reflashar a LittleFS do pedal).
- Carrega mais rápido (arquivos locais) e sem cache velho de PWA.
- A UI aparece mesmo sem o pedal por perto (mostra a tela de conexão do editor).

**O que continua exigindo flash do firmware:** mudanças no **firmware em si**
(novo endpoint/campo de API, comportamento do pedal). A *tela* não.

### 2.2. Resolução do endereço do pedal (`?api=`)

`api.js` do webApp resolve a base da API assim (ordem de prioridade):

1. `?api=<url>` na query string;
2. se rodando de `file://` → default `http://192.168.4.1`;
3. `localStorage['bfmidi_deviceApi']` (IP fixado pela tela de conexão).

O nativo ([MainActivity.kt](app/src/main/java/com/bffx/bfmidi/MainActivity.kt))
**sonda** `http://192.168.4.1` e `http://bfmidi.local`:
- se **algum responde** → carrega `file://…/index.html?api=http://<host>`;
- se **nenhum responde** → carrega **sem** `?api=` → o editor usa o IP fixado
  (localStorage) ou a própria tela de conexão. **Nunca atropela o IP salvo offline.**

### 2.3. Permissão file:// → http

A página `file://` precisa fazer `fetch` cross-origin pro `http://<pedal>`. Isso é
liberado no WebView com (deprecados, mas necessários; app controlado, só LAN):

```kotlin
allowFileAccessFromFileURLs = true
allowUniversalAccessFromFileURLs = true
```

Não é "mixed content" (a página é `file:`, não `https:`). O CORS do firmware é
aberto (`*`), então os fetch/OPTIONS passam.

### 2.4. Uploads

`WebChromeClient.onShowFileChooser` abre o seletor de arquivos do Android (para os
uploads de imagem/ícone do editor). Sem Web Serial/USB — só Wi-Fi.

---

## 3. Estrutura do projeto

```
android_app/
├─ settings.gradle / build.gradle / gradle.properties   # projeto Gradle
├─ app/
│  ├─ build.gradle                                       # config do módulo + signing + versão
│  └─ src/main/
│     ├─ AndroidManifest.xml                             # permissões, cleartext, activity
│     ├─ java/com/bffx/bfmidi/MainActivity.kt            # WebView + sonda + file-access
│     ├─ res/values/{strings,themes}.xml
│     ├─ res/xml/network_security_config.xml             # cleartext liberado (LAN)
│     ├─ res/mipmap-xxxhdpi/ic_launcher.png              # ícone
│     └─ assets/                                         # ★ UI do editor (buildada)
│        ├─ index.html  app.js  app.css  sw.js  manifest.webmanifest
│        └─ icons/…
├─ app/bfmidi-release.jks                                # chave de assinatura FIXA
└─ .github/workflows/build-apk.yml                       # CI
```

---

## 4. Build na nuvem (GitHub Actions)

Não há toolchain Android na máquina de dev — **o build roda no GitHub Actions**.
Workflow: [.github/workflows/build-apk.yml](.github/workflows/build-apk.yml).

Dispara em **push na `main`** ou manualmente (**Actions → Build APK → Run workflow**).
Etapas:

1. `actions/checkout`
2. `setup-java` (Temurin 17) — traz o `keytool`.
3. `android-actions/setup-android` — SDK + licenças.
4. **Garante o keystore fixo** (ver §5).
5. `setup-gradle` (Gradle 8.7).
6. `gradle assembleRelease` (versão vinda do nº do run — ver §6).
7. Renomeia pra `BFMIDI-editor.apk`.
8. Sobe como **artifact** e publica num **Release** (`build-<run>`).

A UI nos `assets/` é empacotada automaticamente pelo `assembleRelease` (já está
commitada no repo — ver §7).

---

## 5. Assinatura (atualização "1 toque")

Pro Android instalar uma atualização **por cima** do app, os APKs precisam ter a
**mesma assinatura**. Build de *debug* gera chave nova a cada run do CI (runner
descartável) → assinaturas diferentes → "app não instalado".

Por isso usamos uma **chave FIXA**:

- `app/bfmidi-release.jks` — gerada **uma vez** pelo próprio CI (`keytool`) e
  **commitada de volta** no repo (com `[skip ci]` pra não disparar build extra).
  Nas execuções seguintes o passo vê que já existe e não faz nada.
- `app/build.gradle` define a `signingConfig release` apontando pra ela.
- Senha (`bfmidi123`) vive no repo. Aceitável: **app pessoal de sideload**; o pior
  caso seria alguém assinar um APK falso com a mesma chave (irrelevante aqui).

> Migração: os builds antigos (build-1/2, debug) tinham chave variável; trocar pra
> a chave fixa exigiu **uma desinstalação final**. Daí em diante, atualização é
> só baixar o APK novo e tocar **"Atualizar"**.

---

## 6. Versionamento automático

`app/build.gradle` lê do ambiente (default `1` / `1.0` em build local):

```gradle
versionCode = BF_VERSION_CODE   (= github.run_number)
versionName = BF_VERSION_NAME   (= "1.0.<run>")
```

O Release usa a tag `build-<run>`. Assim cada build é único e crescente (o Android
sempre trata como atualização, nunca downgrade).

---

## 7. Como atualizar a UI embutida (snapshot)

A `assets/` é a UI **buildada**. O firmware (privado, `PROJECT_ZERO`, **só local**)
**não** entra no repo do APK — só o editor compilado (que já é público em qualquer
pedal). Fluxo quando a tela muda:

```bash
# 1) build do webApp SEM gzip (file:// não decodifica .gz)
cd <repo do firmware>/webApp
BF_NO_GZIP=1 npm run build          # gera ../data/ descompactado

# 2) copia pra dentro do APK
cd ..
rm -rf android_app/app/src/main/assets && mkdir android_app/app/src/main/assets
cp data/index.html data/app.js data/app.css data/manifest.webmanifest data/sw.js \
   android_app/app/src/main/assets/
cp -r data/icons android_app/app/src/main/assets/icons

# 3) commit + push → o CI gera o APK novo
cd android_app && git add -A && git commit -m "Atualiza UI embutida" && git push
```

> **Por que SEM gzip:** o build de produção normal guarda `app.js.gz`/`app.css.gz`
> e o firmware adiciona `Content-Encoding: gzip`. O `file://` do WebView **não**
> descompacta — então a UI embutida precisa dos arquivos crus (`BF_NO_GZIP=1`).

---

## 8. Como atualizar o app NATIVO

Mudanças no Kotlin/manifest/recursos (tela de boot, sonda de IP, permissões):
basta editar e **push na `main`** → o CI gera o APK novo. Nada de assets a copiar.

---

## 9. Fluxo de atualização no celular

1. Abrir a aba **[Releases](https://github.com/bffx-updates/BFMIDI_Android/releases)**
   no celular.
2. Baixar o `BFMIDI-editor.apk` mais novo.
3. Abrir o arquivo → **"Atualizar"** (mesma chave fixa = instala por cima, sem
   desinstalar, sem perder nada — não há dado local relevante; tudo vive no pedal).

---

## 10. Decisões de design (resumo do "porquê")

| Decisão | Motivo |
|---|---|
| WebView (não PWA) | Android não instala PWA de HTTP; WebView ignora a regra. |
| HTTPS self-signed **descartado** | Cert não confiável bloqueia o SW do mesmo jeito + custo de RAM/rewrite no S2. |
| UI embutida no APK | Mudança de tela vira atualização do APK; sem reflash da LittleFS; sem cache velho. |
| Build no GitHub Actions | Sem toolchain Android local. |
| Chave de assinatura fixa | Atualização "1 toque" (mesma assinatura). |
| Snapshot da UI no repo do APK (não o repo do firmware) | `PROJECT_ZERO` fica **local/privado** (código que não pode vazar); só o editor compilado, já público, vai pro repo do APK. |
| `cleartextTrafficPermitted` global | O IP do STA varia; o app só acessa o pedal na LAN. |

---

## 11. Troubleshooting

- **"App não instalado" ao atualizar** → assinatura diferente. Só acontece migrando
  dos builds antigos (debug). Desinstale uma vez e instale o novo; daí em diante ok.
- **UI carrega mas não conecta no pedal** → confira Wi-Fi (mesmo AP/rede do pedal).
  Se mesmo conectado falhar, é ajuste fino de CORS/file-access (flags do §2.3). A
  tela de conexão do editor também deixa fixar o IP manualmente.
- **Tela em branco / assets não carregam** → faltou commitar a `assets/`, ou a UI
  foi buildada **com** gzip (precisa ser `BF_NO_GZIP=1`).
- **Build do CI falhou** → ver **Actions → run → logs**. Causas comuns: AGP×Gradle
  incompatíveis, SDK não baixado, keystore não gerado.
