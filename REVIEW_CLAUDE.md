# Revisión de código para Claude — Isla Dinámica (tap no responde)

Pega este archivo completo en el chat web de Claude y pídele que revise el bug
del toque. Proyecto: app Android en Kotlin, `com.rayo.isladinamica`, una sola
Activity + un servicio de overlay (`IslandService`) que dibuja la isla con
`WindowManager.TYPE_APPLICATION_OVERLAY`. Repo: szcrack/isla-dinamica.

## Síntoma (reportado por el usuario, Motorola Edge 2022, Android 15)

La isla queda alargada (expandida) en pantalla y al presionarla ya no hace
nada: no expande, no colapsa, ningún gesto. Antes el toque sí funcionaba
(v1.4 lo arregló). El teléfono no tiene Wi-Fi ahora mismo, así que no se puede
depurar por ADB: el diagnóstico tiene que ser por lectura de código.

## Código relevante (IslandService.kt)

Ventana del overlay (en `setupOverlay()`):

```kotlin
val params = WindowManager.LayoutParams(
    WindowManager.LayoutParams.WRAP_CONTENT,
    WindowManager.LayoutParams.WRAP_CONTENT,
    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
    PixelFormat.TRANSLUCENT
)
params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
// v1.6 (nuevo): mismo truco que DynamicSpot (OverlayServiceSpot.m0/rg0.d)
params.layoutInDisplayCutoutMode =
    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
try {
    val field = params.javaClass.getField("privateFlags")
    field.isAccessible = true
    field.set(params, (field.get(params) as Int) or 64) // NO_MOVE_ANIMATION
} catch (e: Exception) { }
```

La píldora colapsada mide exactamente el agujero de la cámara
(Rect 505,0-575,105 px en este teléfono): el `collapsedView` se redimensiona
al rect del cutout y el padding del root se pone a 0. La ventana queda en
y=0 (o negativa si el usuario mueve el deslizador vertical, -40..+40dp).

Listeners de toque (también en `setupOverlay()`):

```kotlin
view.setOnClickListener { toggleExpand() }
clockSection?.setOnClickListener { toggleExpand() }
view.setOnTouchListener { _, event ->
    if (event.action == MotionEvent.ACTION_OUTSIDE && isExpanded) {
        collapse()
        true
    } else {
        false
    }
}
notifSection?.setOnClickListener { openCurrentNotif() }   // abre la app
musicSection?.setOnClickListener { openPlayerApp() }      // abre el reproductor
val openAppLongClick = View.OnLongClickListener {
    if (isExpanded) { openCurrentApp(); true } else false
}
view.setOnLongClickListener(openAppLongClick)
notifSection?.setOnLongClickListener(openAppLongClick)
musicSection?.setOnLongClickListener(openAppLongClick)
clockSection?.setOnLongClickListener(openAppLongClick)
```

Estado y animación:

```kotlin
private fun toggleExpand() { if (isExpanded) collapse() else expand() }

private fun expand() {
    if (isExpanded && !isAnimating) return
    isExpanded = true
    repositionPill()
    collapseRunnable?.let { handler.removeCallbacks(it) }
    updateExpandedUi()
    startClockTicker()
    animateSize(toExpanded = true)
    if (isPlaying && currentTheme.showProgressBar) startProgressPolling()
}

private fun collapse() {
    val wasShowingNotif = showingNotification
    isExpanded = false
    stopProgressPolling()
    stopClockTicker()
    showingNotification = false
    currentNotif = null
    hideKeyboard()
    setWindowFocusable(false)
    updateCollapsedUi()
    animateSize(toExpanded = false)
    if (wasShowingNotif && notifQueue.isNotEmpty()) {
        handler.postDelayed({ showNextFromQueue() }, ANIM_MS + 120)
    }
}
```

`animateSize()` anima `params.width/height` con un ValueAnimator y al terminar
llama a `finishSwitch(toExpanded)`, que deja `expandedView` VISIBLE y
`collapsedView` GONE (o al revés) y vuelve los params a WRAP_CONTENT con
`updateViewLayout`. Si el tamaño inicial ya es el objetivo, llama directo a
`finishSwitch`.

Vista previa de posición (MainActivity, al mover los deslizadores):

```kotlin
fun previewPosition() {
    val service = instance ?: return
    if (!service.isExpanded) service.expand()   // NO programa auto-colapso
}
```

El auto-colapso (`scheduleAutoCollapse()`, ~8 s) solo se programa cuando llega
una notificación (`displayNotif()`), no en `expand()` normal ni en el preview.

Layout (`res/layout/island_pill.xml`): root `FrameLayout` (`@+id/pillRoot`,
`wrap_content`, padding 6dp en XML, puesto a 0 por código). Hijos:
`collapsedView` (LinearLayout 52x36dp, redimensionado a 70x105px) y
`expandedView` (LinearLayout 340dp, `visibility=gone` cuando colapsa), con
`clockSection`, `musicSection` (con `albumArt`, `seekBar`, botones),
`notifSection` (con `replyRow` + `EditText` para respuesta en línea).
El `EditText` hace la ventana focusable al tocarlo (`setWindowFocusable(true)`)
y la devuelve a no-focusable al perder el foco.

## Cambios recientes sospechosos

- v1.4: `clockSection` recibió su propio OnClickListener (antes el long-press
  se tragaba el toque) y el preview de posición.
- v1.5: polling cada 2 s con `UsageStatsManager.queryUsageStats` (ventana de
  10 s) para el reloj contextual; clamp `y >= 0` en la posición.
- v1.6: se quitó el clamp (Y negativa permitida), se agregó
  `layoutInDisplayCutoutMode = ALWAYS` y `privateFlags |= 64`, y la detección
  de app al frente pasó a `queryEvents` (último MOVE_TO_FOREGROUND en 10 min).

## Preguntas para Claude

1. ¿Hay algún camino en este código por el que un toque en la píldora
   colapsada NO llegue a `toggleExpand()`? Piensa en: hijos long-clickable
   que se tragan el toque, `FLAG_WATCH_OUTSIDE_TOUCH` + `OnTouchListener`,
   `expandedView` en GONE vs VISIBLE con alpha 0, y la ventana focusable
   tras usar la respuesta en línea.
2. ¿Puede `isExpanded` divergir del estado visible (por ejemplo, quedarse en
   `true` con la isla viéndose colapsada, con lo que `expand()` hace
   early-return y el toque "no hace nada")? Revisa `previewPosition()`,
   `animateSize()` (cancelación del ValueAnimator), `finishSwitch()` y el
   auto-colapso.
3. ¿El preview de posición deja la isla expandida para siempre si el usuario
   mueve el deslizador y luego toca la isla? ¿Qué debería pasar?
4. ¿Afectan `layoutInDisplayCutoutMode = ALWAYS` o `privateFlags |= 64`
   a la recepción de toques en la zona de la barra de estado en Android 15?
5. ¿Qué arreglo concreto recomiendas para que el toque sea a prueba de
   fallos (por ejemplo, detección de tap por `OnTouchListener` con
   DOWN/UP en el root, en vez de depender de `OnClickListener`)? Dame el
   cambio mínimo y seguro.
6. Extra: ¿qué ajustes de DynamicSpot valdría migrar aquí? Sus claves:
   `seekGlowSizeX/Y` (tamaño de la isla), `seekPopupWidth` (ancho del popup),
   `seekPopupTimeoutNew` (tiempo para auto-cerrar), `seekLongPressTime`,
   `prefPopupSwipeLeft/Right` (deslizar para descartar),
   `prefPopupLockscreen`, `prefPopupPosition`, `prefPopupShapeNotch`,
   `prefMusicVisualizer`. ¿Cuáles implementarías primero y cómo?
