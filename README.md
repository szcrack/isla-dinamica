# Isla Dinámica

Dynamic Island para Android: una píldora flotante alrededor de la cámara que
muestra notificaciones y música, con animaciones suaves, tres temas y
permisos auto-otorgados con Shizuku. Sin root.

## Qué hace

- **Notificaciones**: icono de la app, título y texto. Cola de hasta 5: si
  llegan varias se muestran en secuencia (4 s cada una). Al tocar la tarjeta
  abre la app de la notificación.
- **Música**: carátula, título, artista, barra de progreso con tiempos,
  anterior / play-pausa / siguiente. Al tocar la tarjeta abre el reproductor.
  La píldora colapsada muestra la carátula en miniatura mientras suena.
- **Tres temas en vivo** (selector en ajustes, sin reiniciar):
  - **Negro iPhone** (por defecto): negro sólido, texto blanco.
  - **HyperOS Cristal**: vidrio esmerilado translúcido.
  - **HyperOS Brillo**: oscuro con borde de luz azul-violeta.
- **Shizuku**: la app se auto-otorga `WRITE_SECURE_SETTINGS`, el acceso de
  escucha de notificaciones, el permiso de superposición y la exención de
  optimización de batería. Incluye el interruptor **"Ocultar iconos de la
  barra de estado"** (escribe `icon_blacklist`). Si Shizuku no está corriendo,
  los botones manuales de Ajustes siguen como alternativa.
- **Ligera**: el progreso solo se actualiza mientras la tarjeta está expandida
  y suena música; sin hilos de fondo.

## Permisos que necesita

- Mostrar sobre otras apps (superposición).
- Acceso a notificaciones (Notification Listener).
- `POST_NOTIFICATIONS` (Android 13+).
- Shizuku (opcional, para auto-otorgarse los permisos de arriba).

## Compilar

```bash
./gradlew assembleDebug
# APK en app/build/outputs/apk/debug/app-debug.apk
```

Requiere JDK 17 y Android SDK con `platforms;android-34` y
`build-tools;34.0.0`. Cada push a `main` también compila el APK en
GitHub Actions (`.github/workflows/build-apk.yml`) y lo deja como artefacto
por 14 días.

## Instalar en el teléfono

```bash
adb install -r app-debug.apk
```

Luego, en la app, toca **"Iniciar isla"** y concede el permiso de Shizuku
si te lo pide. Los permisos de superposición, notificaciones,
`WRITE_SECURE_SETTINGS` y batería también se pueden otorgar por ADB:

```bash
appops set --uid com.rayo.isladinamica SYSTEM_ALERT_WINDOW allow
cmd notification allow_listener com.rayo.isladinamica/.IslandNotificationListener
pm grant com.rayo.isladinamica android.permission.WRITE_SECURE_SETTINGS
dumpsys deviceidle whitelist +com.rayo.isladinamica
```

## Estado

- **v1.1** (versionCode 2): notificaciones + música, 3 temas, Shizuku,
  instalada y verificada en Motorola Edge (2022), Android 15.
- **v1.2** (versionCode 3): isla centrada en la cámara (usa el recorte real
  de pantalla) con deslizadores X/Y en ajustes para afinar la posición;
  píldora colapsada chica abrazando la cámara; un toque expande y muestra
  hora + fecha arriba; el interruptor oculta iconos de la barra con
  `cmd statusbar send-disable-flag` (mismo mecanismo que Essentials) más
  `icon_blacklist` de respaldo. Corrección posterior: el flag `clock`
  también escondía el reloj del panel de control, por eso en v1.3 se quitó
  de los flags y el reloj de la barra se oculta solo con `icon_blacklist`.
- **v1.3** (versionCode 4): el reloj del panel de control vuelve (el flag
  `clock` ya no se usa; la barra sigue sin reloj gracias a `icon_blacklist`);
  la píldora colapsada tiene el tamaño exacto del orificio de la cámara
  (rect del recorte de pantalla, en píxeles, sin conversión a dp); la barra
  de progreso de música es ahora una SeekBar que permite adelantar/atrasar
  la canción al arrastrarla; tocar fuera de la isla expandida la cierra
  (`FLAG_WATCH_OUTSIDE_TOUCH`); la posición X/Y se reaplica al expandir
  por si algún cambio de deslizador se perdió; toque largo con la isla
  expandida abre la app (la de la notificación o el reproductor); responder
  mensajes dentro de la isla con un campo minimalista (RemoteInput, solo
  aparece cuando la notificación admite respuesta directa, p. ej. WhatsApp).
- **v1.4** (versionCode 5): tocar la parte de arriba de la isla expandida
  (la zona del reloj, bajo la cámara) la minimiza de nuevo — en v1.3 el
  toque largo de esa zona se tragaba el toque; tocar fuera sigue cerrándola.
  Al mover los deslizadores de posición la isla se expande sola como vista
  previa en vivo (la píldora colapsada es del tamaño del orificio y no se
  ve al moverla); botón "Centrar isla en la cámara" para volver a 0,0.
  Reloj del panel: en v1.3 se quitó `clock` de los flags pero el reloj del
  panel seguía sin aparecer, así que se está probando si es `icon_blacklist`
  quien lo esconde (prueba en el teléfono: lista negra solo con
  `alarm_clock`, sin `clock`).
