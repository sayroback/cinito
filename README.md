# CiniTo

**CiniTo** (`org.cinito.online`) es un reproductor y cliente de streaming BitTorrent de código abierto para Android con soporte de envío a TV (Google Cast, LG webOS SSAP y DLNA/UPnP).

> **Estado actual:** `0.1.3-alpha.1` (versión alfa en desarrollo activo).  
> **Licencia:** [GNU General Public License v3.0 or later (GPL-3.0-or-later)](LICENSE).

---

## Características principales

- **Motor BitTorrent en Rust (`engine-rs`):** streaming secuencial por HTTP local (`127.0.0.1`), índice de búsqueda rápida (*seek*), verificación de piezas y gestión de almacenamiento.
- **Reproducción local avanzada:** Jetpack Compose + AndroidX Media3 (ExoPlayer) + NextLib (`FFmpeg` y `libass` para subtítulos `.ass`/`.ssa`).
- **Envío a TV (Cast):**
  - **Google Cast V2** por TLS + descubrimiento mDNS.
  - **LG webOS** vía SSAP (`wss://:3001` / `ws://:3000`), app receptora HTML5 (`webos-receiver/`) y navegador webOS.
  - **DLNA / UPnP** con control de reproducción y conmutación de subtítulos en dispositivos compatibles.
- **Privacidad y red endurecida:** sin anuncios, sin SDKs de analítica ni telemetría, soporte DNS-over-HTTPS (DoH), bloqueo de tráfico cleartext hacia internet pública y almacenamiento cifrado con Android Keystore para claves locales. Consulta [PRIVACY.md](PRIVACY.md).

---

## Arquitectura del repositorio

| Ruta | Responsabilidad |
|---|---|
| `app/` | Aplicación Android (`org.cinito.online`), UI Jetpack Compose, Cast, probing de contenedores y reproductor Media3 |
| `core/` | Contrato del cliente Kotlin hacia el motor (`EngineClient`) |
| `engine-rs/` | Motor BitTorrent en Rust compilado vía NDK a `libengine.so` |
| `webos-receiver/` | Receptor HTML5 compartido (`/watch`) y empaquetable para LG webOS (`org.cinito.tv`) |
| `tools/` | Utilidades de diagnóstico LAN y puente de pruebas para emulador |
| `docs/` | Guías operativas y de depuración en red local |

---

## Requisitos y compilación

- **Android SDK** (compileSdk `36`, minSdk `26`, targetSdk `36`) y **NDK `26.1.10909125`**.
- **JDK 21** (usado por Gradle `9.6.0`).
- **Rust (`rustup`)** con target `aarch64-linux-android` (y `x86_64-linux-android` si compilas para emulador x86_64).

Comandos principales desde la raíz del repositorio:

```bash
# Ejecutar pruebas unitarias JVM de Android
./gradlew testDebugUnitTest

# Compilar e instalar APK debug en dispositivo/emulador conectado
./gradlew installDebug

# Compilar incluyendo ABI x86_64 para emulador
./gradlew installDebug -Pcinito.emulator=true

# Ejecutar pruebas unitarias del motor Rust
cd engine-rs && cargo test -p wt-engine --lib

# Verificar sintaxis JS del receptor TV
node --check webos-receiver/assets/webos_receiver/player.js
```

---

## Versionado

La versión de la aplicación se centraliza en `gradle.properties`:

- `APP_VERSION_NAME` (actualmente `0.1.3-alpha.1`)
- `APP_VERSION_CODE` (entero estrictamente incremental, actualmente `1`)

El receptor webOS (`webos-receiver/appinfo.json`) mantiene el número base `MAJOR.MINOR.PATCH` (`0.1.3`).

---

## Contribuir

Este proyecto sigue estrictamente **GitHub Flow**: no se permiten commits ni pushes directos a `main`. Lee [CONTRIBUTING.md](CONTRIBUTING.md) antes de abrir un Pull Request.

---

## Atribución y privacidad

- Licencias y proyectos base: [ATTRIBUTION.md](ATTRIBUTION.md)
- Política de privacidad y conexiones de red: [PRIVACY.md](PRIVACY.md)
