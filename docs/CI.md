# Pipeline di build e firma

## Workflow

| File | Quando | Cosa fa |
|---|---|---|
| `.github/workflows/android-build.yml` | ogni `push`, `pull_request`, manuale | JDK 17 → Android SDK → Gradle (con cache) → **test** (dominio, database, dati, UI/Robolectric) → **APK debug** → carica gli artifact `gestione-spese-debug-apk` e `test-reports` |
| `.github/workflows/android-release.yml` | manuale (Run workflow) o tag `v*` | verifica i segreti → test → **APK release firmato** → verifica la firma con `apksigner` → artifact `gestione-spese-release-apk` (e GitHub Release se è un tag) |

L'APK debug usa `applicationId` `it.spese.gestione.debug` e la chiave di debug di Android: si installa accanto alla versione release.

## Configurare la firma (una tantum)

1. Genera un keystore (conservalo al sicuro: senza di esso non potrai aggiornare l'app):
   ```bash
   keytool -genkeypair -v -keystore release.jks -alias gestione-spese -keyalg RSA -keysize 2048 -validity 10000
   base64 -w0 release.jks     # macOS: base64 -i release.jks
   ```
2. In GitHub: *Settings → Secrets and variables → Actions → New repository secret*:

   | Secret | Valore |
   |---|---|
   | `ANDROID_KEYSTORE_BASE64` | l'output di `base64` |
   | `ANDROID_KEYSTORE_PASSWORD` | password del keystore |
   | `ANDROID_KEY_ALIAS` | `gestione-spese` (o l'alias scelto) |
   | `ANDROID_KEY_PASSWORD` | password della chiave |
3. *Actions → Android release firmata → Run workflow*. L'APK firmato è negli artifact (o nella Release se pubblichi un tag `v1.0.0`).

Senza i segreti il workflow di release si ferma con un messaggio che elenca quelli mancanti; la build `assembleRelease` locale produce un APK **non firmato** (mai firmato con la chiave di debug).
Il keystore non è mai nel repository (`*.jks`, `*.keystore`, `keystore.properties` sono in `.gitignore`); nel runner viene decodificato in una cartella temporanea e rimosso a fine job.

## Build locale

```bash
./gradlew :domain:test                       # motori finanziari (veloce)
./gradlew testDebugUnitTest                  # database, backup, UI (Robolectric)
./gradlew :app:assembleDebug                 # app/build/outputs/apk/debug/app-debug.apk
# release firmata in locale: KEYSTORE_PATH/KEYSTORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD oppure keystore.properties (non versionato)
./gradlew :app:assembleRelease
```
Requisiti: JDK 17+, Android SDK con `platforms;android-35` e `build-tools;35.0.0` (`local.properties` con `sdk.dir`).
