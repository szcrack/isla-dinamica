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
  hora + fecha arriba; el interruptor ahora oculta reloj, iconos del sistema
  e iconos de notificación de la barra con `cmd statusbar send-disable-flag`
  (mismo mecanismo que Essentials) más `icon_blacklist` de respaldo — el
  reloj del panel de control sigue visible.
