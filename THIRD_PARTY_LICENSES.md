# Licencias y avisos de terceros

## Remux Folder

El código de esta aplicación se distribuye bajo la licencia MIT incluida en `LICENSE`.

## FFmpegKit Maintained

- Artefacto: `dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.9`.
- Licencia del artefacto: LGPL-3.0.
- Código y avisos del proveedor: [ffmpegkit-maintained/ffmpeg](https://github.com/ffmpegkit-maintained/ffmpeg).
- Texto de LGPL-3.0: [gnu.org/licenses/lgpl-3.0.html](https://www.gnu.org/licenses/lgpl-3.0.html).

Remux Folder invoca FFmpeg con `-c copy`; no necesita codificadores GPL y no depende de una variante `-gpl`. La versión completa LGPL se usa para ofrecer FFprobe y compatibilidad amplia de lectura y muxing.

El AAR incluye `THIRD-PARTY-NOTICES.txt` del proveedor. La app muestra ese archivo desde la pantalla «Licencias» cuando está presente en el paquete. Al distribuir APKs, conserva esos avisos junto con la licencia del proyecto y la información necesaria para cumplir LGPL, incluida la posibilidad de reemplazar o relinkar la biblioteca. Revisa las condiciones de distribución de la dependencia antes de publicar una versión.

## AndroidX y Compose

Las dependencias directas de AndroidX, Jetpack Compose, Material 3, DocumentFile y WorkManager se distribuyen bajo Apache License 2.0. Sus avisos se encuentran en [la licencia Apache 2.0](https://www.apache.org/licenses/LICENSE-2.0) y en los metadatos de cada artefacto publicado por AndroidX.

JUnit 4.13.2 solo se usa para pruebas y no se incluye en el APK. JUnit se distribuye bajo Eclipse Public License 1.0; consulta [eclipse.org/legal/epl-v10.html](https://www.eclipse.org/legal/epl-v10.html).
