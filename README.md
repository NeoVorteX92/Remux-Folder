# Remux Folder

Remux Folder es una app Android libre para cambiar el contenedor de muchos videos de una sola vez. Eliges una carpeta y la app revisa sus videos y subcarpetas. Los resultados quedan **dentro** de la carpeta elegida, en `<nombre> (remux)`, con la estructura de subcarpetas conservada. También reconoce y reutiliza la carpeta marcada con el nombre antiguo `<nombre>remux`.

## Por qué elegir Remux Folder

Si solo quieres cambiar el contenedor, una conversión completa hace trabajo innecesario. Remux Folder copia las pistas sin recodificarlas: conserva su calidad original y normalmente termina mucho más rápido que volver a codificar video y audio.

- **Primero analiza y deja elegir.** La vista previa muestra los archivos y su tamaño, permite marcar solo algunos y analiza las pistas antes del lote. El audio nunca desaparece entero a menos que actives esa opción de forma explícita.
- **Remux sin volver a comprimir.** FFmpeg recibe mapas por índice y copia video/audio sin cambiar su calidad. Puede adaptar subtítulos de texto entre contenedores; nunca recodifica las pistas de video o audio.
- **Comprueba antes de publicar.** Escribe un temporal `.part`, comprueba pistas y duración con FFprobe y recién entonces le da el nombre final. MP4/MOV se preparan en un archivo temporal local para permitir `faststart`, luego se copian a la carpeta elegida.
- **Reintenta con cuidado.** Si el muxer señala una pista incompatible, intenta una vez más sin esa pista y deja constancia de la pérdida. En MKV deja que el muxer pruebe códecs menos comunes.
- **Protege los originales.** Remux Folder no modifica ni borra videos originales. Si un nombre de salida ya existe, usa otro nombre; si una carpeta de salida existente no tiene el marcador de Remux Folder, se detiene.
- **Conserva los resultados completos.** Cada archivo mantiene estado, motivo, advertencias y registro local; puedes compartir el registro completo o reintentar solo los fallidos.
- **Trabaja en el dispositivo.** No requiere cuenta, servidor ni conexión. La app no declara permiso de Internet y no envía videos a ningún servicio.
- **Comprueba su propio motor.** En la versión debug, el diagnóstico muestra y copia versión/configuración y listas de muxers, demuxers, encoders y filtros. También puede probar lectura/escritura temporal de la carpeta elegida, sin tocar videos.
- **Es abierta y reproducible.** El proyecto incluye pruebas unitarias y GitHub Actions para revisar, compilar y publicar APKs como artefactos.

Su enfoque es concreto: procesamiento por carpetas, sin recodificación y con una revisión antes y después. No intenta ser un editor ni una suite de conversión con cientos de controles.

## Contenedores

MP4, MKV, MOV, TS, AVI y WebM.

La compatibilidad se determina por tipo de pista y códec; para MKV se intenta una selección más amplia y el muxer decide. La app no cambia video o audio para resolver incompatibilidades: eso requeriría recodificar y queda fuera de su objetivo. Si se descarta una pista, la lista de resultados lo explica.

## Requisitos

- Android 7.0 o posterior (API 24).
- Dispositivo arm64 o emulador x86_64. No se distribuyen bibliotecas de 32 bits.
- Espacio disponible para las salidas. El escaneo muestra tamaño total, tamaño máximo por temporal y espacio interno libre; el proveedor SAF puede no revelar el espacio disponible de SD, nube o USB.
- El proyecto usa `targetSdk 36`, requerido para nuevas apps y actualizaciones de Google Play desde el 31 de agosto de 2026 ([política vigente](https://support.google.com/googleplay/android-developer/answer/11926878)).

## Trabajo en segundo plano

Remux Folder mantiene el trabajo visible con WorkManager y una notificación cancelable. Android 15 limita los servicios `mediaProcessing` a seis horas por cada período de 24 horas; Android 16 también aplica cuotas a trabajos largos de WorkManager ([límites de Android](https://developer.android.com/develop/background-work/services/fgs/timeout), [trabajos largos](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running)). Si Android interrumpe un lote muy grande, sus salidas ya publicadas permanecen. Al repetir el lote, la app valida y reutiliza las salidas completas y limpia temporales abandonados.

La app mantiene un `PARTIAL_WAKE_LOCK` con vencimiento y lo libera al terminar. Si el sistema detiene el trabajo, conserva los resultados ya guardados; vuelve a iniciar el lote desde la app para continuar y reutilizar las salidas verificadas. La app no añade una opción para borrar los originales.

## Compilar con GitHub Actions

El workflow `.github/workflows/android.yml` ejecuta pruebas unitarias, lint y compilaciones debug y release en cada push, pull request o ejecución manual. Descarga el artefacto `remux-folder-apks` desde la ejecución de Actions. El APK debug queda firmado para instalarlo; el release queda sin firmar para firmarlo antes de distribuirlo. Las pruebas y la compilación de esta revisión quedan pendientes de ejecutar en GitHub Actions.

El proyecto no requiere compilarse en una PC local.

### Pasos para obtener el APK

1. Crea en GitHub un repositorio público para Remux Folder.
2. Extrae `RemuxFolder-project.zip` y sube todo su contenido, incluida la carpeta `.github`, al repositorio. Confirma la subida creando un commit.
3. Abre la pestaña **Actions** del repositorio y espera a que termine **Android build**.
4. En esa ejecución descarga el artefacto **remux-folder-apks**. Dentro del ZIP, instala `app-debug.apk` en el teléfono.

La primera instalación puede requerir que Android permita instalar esta app desde la ubicación donde descargaste el APK. El artefacto de release aún no está firmado para distribución.

## Licencias

El código de Remux Folder usa MIT. FFmpegKit Maintained se incluye como `dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.9`, bajo LGPL-3.0; no se usa una variante GPL. Al redistribuir APKs, conserva los avisos incluidos por la dependencia y consulta [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).
