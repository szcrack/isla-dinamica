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
- **v1.5** (versionCode 6): el reloj de la barra ahora es contextual: se
  oculta solo en el inicio del launcher y reaparece en otras apps y en el
  panel de control. Lo detecta con UsageStats (permiso de acceso a uso,
  se concede en Ajustes desde la app); sin ese permiso el reloj se oculta
  en todas partes mientras el interruptor esté activo. La posición vertical
  ya no puede sacar la isla de la pantalla (y >= 0): la cámara está en el
  borde superior, así que hacia arriba no hay más que el borde — el ajuste
  útil es hacia abajo.
- **v1.6** (versionCode 7): híbrido con DynamicSpot — la posición vertical
  ya puede subir de verdad (Y negativa, sin el tope de v1.5),
  `layoutInDisplayCutoutMode=ALWAYS` + `privateFlags|=64` en la ventana de
  la píldora; la detección de la app al frente usa el flujo de eventos de
  UsageStats (último `MOVE_TO_FOREGROUND`, 10 min) con fallback a stats
  de 60 s, así el launcher quieto ya no se pierde y el reloj no vuelve solo.
- **v1.7** (versionCode 8): detección de gestos en crudo sobre la raíz
  (toque <350 ms y <12 dp = abrir/cerrar, largo = abrir app, deslizar
  >60 dp = cerrar; todo con log `IslaDinamica`); la vista previa se cierra
  sola a los 2 s; ajustes de DynamicSpot migrados (tamaño 50–150%, ancho
  de tarjeta 280–420 dp, auto-cierre 2–30 s, largo 300–1500 ms, deslizar
  para cerrar); banner de música estilo Essentials (carátula 112 dp, p.
  ej. miniatura de YouTube).
- **v1.8** (versionCode 9, revisión de Claude al bug del toque):
  `toggleExpand()` decide por lo visible, Toast temporal "Isla:
  abrir/cerrar", guardia de generación en el animador, zona táctil
  colapsada ~51 dp con padding transparente, `FLAG_LAYOUT_NO_LIMITS`,
  las secciones colapsan si no hay nada que abrir, `FLAG_NOT_TOUCH_MODAL`
  cuando la ventana es enfocable. No se instaló (la sustituyó v1.9).
- **v1.9** (versionCode 10): auto-cierre configurable de Claude
  (`popup_timeout_ms`, deslizador 0–20 s con "Nunca" en 0, no se cierra
  mientras se escribe una respuesta).
- **v1.10** (versionCode 11): resto de DynamicSpot de la lista de Claude
  + vista previa unificada (`scheduleAutoCollapse(3000)` en cada
  movimiento): interruptor de pantalla de bloqueo (oculta la isla con el
  teléfono bloqueado), interruptor de forma de muesca (agujero exacto vs
  píldora estándar 64x48) y visualizador de música (5 barras animadas en
  la tarjeta mientras suena; decorativo, un visualizador real necesitaría
  `CAPTURE_AUDIO_OUTPUT`).
- **v1.11** (versionCode 12, guía `docs/guia-v1.11.md`): calibración del
  tamaño exacto de la cámara (deslizadores Ancho/Alto/Y en píxeles, con
  overlay rojo mientras se ajusta; se suma al offset Y, no lo pisa);
  servicio de accesibilidad `FrenteService` (detección instantánea de la
  app al frente y overlay `TYPE_ACCESSIBILITY_OVERLAY` por encima de la
  barra; opcional, se activa a mano en Ajustes); `notification-peek` en
  los flags para que las notificaciones flotantes del sistema no se
  dupliquen con la isla; restauración segura de la barra (al morir el
  servicio, al apagarse la pantalla y botón manual "Restaurar barra");
  filtro de notificaciones duplicadas (resúmenes de grupo y mismo texto
  en 2 s); animación elástica al expandir/colapsar (`PathInterpolator`,
  380 ms); anillo de carga alrededor de la cámara al conectar/desconectar
  el cargador (verde, rojo con 15% o menos). El mecanismo del reloj NO
  cambió a flags `clock` (ese flag también esconde el reloj del panel):
  sigue siendo `icon_blacklist` contextual.
