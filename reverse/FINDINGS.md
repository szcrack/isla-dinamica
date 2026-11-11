# Ingeniería inversa a DynamicSpot 3.00 (com.jamworks.dynamicspot)

APK extraído del teléfono de Zcrack por ADB el 2026-11-10 (~7.5 MB, base.apk),
descompilado con jadx 1.5.2. Archivo: `dynamicspot.apk` (este directorio).
Clase principal: `OverlayServiceSpot` (7,688 líneas descompiladas, 25 errores
de jadx; el método M2() no se pudo descompilar).

## Hallazgos verificados en el DEX

1. **DynamicSpot NO oculta el reloj.** En ninguno de los dos dex existe la
   cadena `icon_blacklist`, ni `send-disable-flag`, ni ninguna escritura a
   `Settings.Secure`. El reloj que vuelve NO es culpa de DynamicSpot. Su
   `WRITE_SECURE_SETTINGS` declarado en el manifest no se usa para la barra
   de estado (los únicos strings con ese permiso son scripts de ayuda de
   bxActions, otra app del mismo autor, en `a.java`). El ocultado de iconos
   es un truco de Essentials (sameerasw/essentials, MIT), que ya usamos.

2. **Posición: Y negativa, ese es el truco.** La ventana principal de la
   isla (`z2()`):
   - `WindowManager.LayoutParams(50dp, 50dp, TYPE_APPLICATION_OVERLAY(2032), flags, TRANSLUCENT(-3))`
   - `gravity = 49` (TOP | CENTER_HORIZONTAL)
   - `F1.y = (-k0(4.0f)) + seekGlowY` → por defecto **-4dp** + offset del
     usuario; en modo notch `-8.5dp`. Los popups usan `y = -k0(2.0f)` (-2dp).
   - Offsets del usuario en prefs: `seekGlowX` / `seekGlowY` (sliders).
   - `rg0.d()`: en rotación copia los params y pone
     `layoutInDisplayCutoutMode = 3` (ALWAYS) en API 30+.
   - `m0()`: por reflexión pone `privateFlags |= 64`
     (PRIVATE_FLAG_NO_MOVE_ANIMATION) para que mover la ventana no anime.
   - La píldora es de 50dp, más grande que el agujero de la cámara, así que
     con -4dp sigue cubriendo la cámara y se ve "más arriba que la barra".

3. **Detección de primer plano: AccessibilityService, no UsageStats.**
   `OverlayServiceSpot extends AccessibilityService`. En
   `onAccessibilityEvent` reacciona a `TYPE_WINDOWS_CHANGED` (4194304) y a
   cambios de bounds (`getWindowChanges() == 8`) reacomodando la isla
   (`v1(config)` + `M2()`). También consulta `KeyguardManager.isKeyguardLocked()`.
   Tiene un `UsageStatsManager` guardado (`s0`) pero no se usa en el código
   descompilado (probablemente código muerto o dentro de M2()).

4. **Animaciones:** `OvershootInterpolator(1.22f)`, animación `drop_in`,
   duraciones de 350ms.

## Bug propio encontrado al comparar (v1.5)

`foregroundPackage()` usaba `queryUsageStats(INTERVAL_BEST, now-10s, now)` y
tomaba el `maxByOrNull { lastTimeUsed }`. Una app en primer plano pero
inactiva (el launcher, sin hacer nada) no tiene uso en los últimos 10s, así
que no aparece en la ventana y gana el evento viejo de otra app → el reloj
vuelve "al rato" solo. Es el síntoma que reportó el usuario.

## Lo que se copió a la v1.6 (híbrido)

- Se quitó el clamp `y >= 0` de `computePillPosition()`: el slider vertical
  (-40..+40dp) ahora mueve la ventana de verdad, también hacia arriba
  (Y negativa, como DynamicSpot).
- `layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS` en la
  ventana de la píldora.
- `privateFlags |= 64` por reflexión (mismo truco de `m0()`).
- `foregroundPackage()` ahora usa `queryEvents` (últimos 10 min, último
  `MOVE_TO_FOREGROUND`) con fallback a `queryUsageStats` de 60s: el launcher
  inactivo sigue detectándose y el reloj ya no debería volver solo.
- El ocultado de iconos sigue siendo el mecanismo de Essentials
  (`icon_blacklist` + `cmd statusbar`), que DynamicSpot ni siquiera tiene.
