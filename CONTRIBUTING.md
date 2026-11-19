# Guía de contribución — GitHub Flow

Este repositorio sigue **estrictamente GitHub Flow**.  
La rama `main` debe mantenerse siempre estable, compilable y lista para liberar. **Nadie** (ni colaboradores ni el gestor del repositorio) debe hacer commits o `git push` directamente sobre `main`.

---

## Reglas fundamentales

1. **`main` es solo de lectura directa:** Todo cambio entra únicamente mediante **Pull Request (PR)** revisado e integrado en `main`.
2. **Una rama por cada cambio:** Crea siempre una rama nueva partiendo de `main` actualizada.
3. **Pruebas obligatorias antes del PR:** No abras un PR con tests rotos ni errores de compilación.
4. **Cero secretos o archivos locales:** Nunca incluyas `local.properties`, keystores (`.jks`, `.keystore`), certificados, archivos `.env`, ni rutas o credenciales personales.

---

## Flujo paso a paso (GitHub Flow)

### 1. Actualizar `main` local

```bash
git checkout main
git pull origin main
```

### 2. Crear una rama descriptiva

Usa prefijos claros según el tipo de cambio:

- `feat/<nombre-corto>` — nueva funcionalidad
- `fix/<nombre-corto>` — corrección de error
- `refactor/<nombre-corto>` — mejora interna sin cambiar comportamiento
- `docs/<nombre-corto>` — documentación
- `test/<nombre-corto>` — pruebas
- `chore/<nombre-corto>` — mantenimiento, dependencias o CI

Ejemplo:

```bash
git checkout -b fix/dlna-subtitle-toggle
```

### 3. Hacer commits claros

Usa [Conventional Commits](https://www.conventionalcommits.org/):

```bash
git add <archivos>
git commit -m "fix(cast): retry DLNA SetAVTransportURI on fault 701"
```

### 4. Verificar antes de subir

Ejecuta las comprobaciones correspondientes a los módulos que hayas tocado:

```bash
# Pruebas unitarias Android
./gradlew testDebugUnitTest

# Pruebas unitarias Rust (si modificaste engine-rs/)
cd engine-rs && cargo test -p wt-engine --lib

# Sintaxis JS del receptor TV (si modificaste webos-receiver/)
node --check webos-receiver/assets/webos_receiver/player.js
```

### 5. Subir la rama y abrir Pull Request

```bash
git push -u origin fix/dlna-subtitle-toggle
```

En GitHub:
1. Abre un **Pull Request** desde tu rama hacia `main`.
2. Describe qué cambia, por qué y cómo se probó.
3. Espera la revisión del gestor del repositorio y resuelve cualquier comentario.
4. Una vez aprobado, el gestor integra el PR en `main` (preferiblemente con *Squash and merge* o *Merge pull request*) y se elimina la rama de trabajo.

### 6. Limpiar tu rama local tras el merge

```bash
git checkout main
git pull origin main
git branch -d fix/dlna-subtitle-toggle
```

---

## Control de versiones (Alfa / Releases)

- Toda versión pública se gestiona mediante PR actualizando `APP_VERSION_NAME` y `APP_VERSION_CODE` en `gradle.properties` (y `version` en `webos-receiver/appinfo.json` cuando corresponda).
- `APP_VERSION_CODE` debe incrementar en `+1` en cada release.
- Secuencia recomendada: `0.1.3-alpha.1` → `0.1.3-alpha.2` → `0.1.3-beta.1` → `0.1.3`.
- Una vez integrado el PR en `main`, el gestor crea la etiqueta (`git tag v0.1.3-alpha.2`) y publica el Release en GitHub.
