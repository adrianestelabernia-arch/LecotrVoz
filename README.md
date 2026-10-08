# Lector Voz

App Android (Kotlin + Jetpack Compose) que lee en voz alta txt, md, html, epub y pdf (con OCR de ML Kit para PDF escaneados).

## Obtener el APK (sin instalar nada)
1. Crea un repositorio en GitHub y sube TODO el contenido de esta carpeta (incluida `.github`).
2. Ve a la pestaña **Actions** → "Build APK" (se ejecuta solo al subir; también con *Run workflow*).
3. Cuando termine (~5-8 min), abre la ejecución y descarga el artefacto **LectorVoz-apk** (contiene `app-debug.apk`).
4. Pásalo al móvil e instálalo (permitir "instalar apps desconocidas").

## Compilar en local
Abre la carpeta en Android Studio (Koala o superior) y ejecuta *Run*, o `gradle :app:assembleDebug` con Gradle 8.9 y JDK 17.
