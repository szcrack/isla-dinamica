# Seguimiento para Claude — animateSize, finishSwitch y clickable del XML

Aquí va lo que pediste para confirmar la causa. Ya apliqué tus
recomendaciones en la v1.8 y v1.9 (toggle por visibilidad, guardia de
generación en el animador, zona táctil de ~51dp, colapso cuando la
sección está vacía, FLAG_NOT_TOUCH_MODAL, timeout con "Nunca"). Dime si
con este código la causa queda confirmada o si ves algo más.

## animateSize() (actual, con la guardia que sugeriste)

```kotlin
private fun animateSize(toExpanded: Boolean) {
    val root = pillView ?: run { finishSwitch(toExpanded); return }
    val params = root.layoutParams as? WindowManager.LayoutParams
        ?: run { finishSwitch(toExpanded); return }
    sizeAnimator?.cancel()
    val startW = params.width.takeIf { it > 0 } ?: root.width
    val startH = params.height.takeIf { it > 0 } ?: root.height
    val targetW = if (toExpanded) popupWidthPx() else collapsedSizePx().first
    val targetH = if (toExpanded) measureExpandedHeight() else collapsedSizePx().second
    if (startW == targetW && startH == targetH) {
        finishSwitch(toExpanded)
        return
    }
    if (toExpanded) {
        expandedView?.visibility = View.VISIBLE
        expandedView?.alpha = 0f
        collapsedView?.visibility = View.VISIBLE
        collapsedView?.alpha = 1f
        root.setBackgroundResource(currentTheme.cardBackgroundRes)
    } else {
        expandedView?.visibility = View.VISIBLE
        expandedView?.alpha = 1f
        collapsedView?.visibility = View.VISIBLE
        collapsedView?.alpha = 0f
    }
    isAnimating = true
    val gen = ++animGeneration
    val anim = ValueAnimator.ofFloat(0f, 1f)
    anim.duration = ANIM_MS
    anim.interpolator = AccelerateDecelerateInterpolator()
    anim.addUpdateListener { va ->
        val f = va.animatedValue as Float
        params.width = (startW + ((targetW - startW) * f)).toInt()
        params.height = (startH + ((targetH - startH) * f)).toInt()
        try {
            windowManager.updateViewLayout(root, params)
        } catch (e: Exception) {
            // Window is gone; the animation will finish below.
        }
        if (toExpanded) {
            expandedView?.alpha = f
            collapsedView?.alpha = 1f - f
        } else {
            expandedView?.alpha = 1f - f
            collapsedView?.alpha = f
        }
    }
    anim.addListener(object : AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: Animator) {
            if (gen != animGeneration) return
            isAnimating = false
            sizeAnimator = null
            finishSwitch(toExpanded)
        }

        override fun onAnimationCancel(animation: Animator) {
            if (gen != animGeneration) return
            isAnimating = false
        }
    })
    sizeAnimator = anim
    anim.start()
}
```

## finishSwitch() (actual, con el padding táctil)

```kotlin
private fun finishSwitch(toExpanded: Boolean) {
    if (toExpanded) {
        expandedView?.visibility = View.VISIBLE
        expandedView?.alpha = 1f
        collapsedView?.visibility = View.GONE
        pillView?.setBackgroundResource(currentTheme.cardBackgroundRes)
        pillView?.setPadding(0, 0, 0, 0)
    } else {
        expandedView?.visibility = View.GONE
        collapsedView?.visibility = View.VISIBLE
        collapsedView?.alpha = 1f
        pillView?.setBackgroundResource(currentTheme.pillBackgroundRes)
        val side = dp(12f)
        pillView?.setPadding(side, 0, side, 0)
    }
    val root = pillView ?: return
    val params = root.layoutParams as? WindowManager.LayoutParams ?: return
    params.width = WindowManager.LayoutParams.WRAP_CONTENT
    params.height = WindowManager.LayoutParams.WRAP_CONTENT
    try {
        windowManager.updateViewLayout(root, params)
    } catch (e: Exception) {
        // Window is gone.
    }
}
```

## Atributos clickable del XML (island_pill.xml)

El XML **no tiene ningún `android:clickable` ni `android:longClickable`**
(0 ocurrencias). La clickabilidad viene del código y del tipo de widget:

- Root `pillRoot` (FrameLayout): sin clickable en XML. En código lleva el
  `OnTouchListener` de gestos (tap / swipe / long-press) y antes llevaba
  `setOnClickListener { toggleExpand() }`.
- `collapsedView` (LinearLayout): sin clickable; el toque pasa al root.
- `expandedView` (LinearLayout, 340dp→ ahora `match_parent`): sin clickable.
- `clockSection` (LinearLayout): `setOnClickListener { toggleExpand() }` y
  `setOnLongClickListener` (abre la app) en código.
- `notifSection` (LinearLayout): `setOnClickListener { if (!openCurrentNotif()) collapse() }`
  y `setOnLongClickListener` en código.
- `musicSection` (LinearLayout): `setOnClickListener { if (!openPlayerApp()) collapse() }`
  y `setOnLongClickListener` en código.
- `seekBar` (SeekBar): clickable/focusable por ser SeekBar; tiene su propio
  `OnSeekBarChangeListener` (seekTo al soltar).
- `btnPrev`, `btnPlayPause`, `btnNext` (ImageButton): clickables por ser
  botones, con sus listeners de transporte.
- `replyRow` con `replyInput` (EditText): focusable por ser EditText; al
  tocarlo la ventana se vuelve focusable para que salga el teclado.
- `btnSendReply` (ImageButton): envía la respuesta en línea.

Pregunta: con este código, ¿queda confirmada la causa del bug (desincronización
flag/visible + preview sin auto-cierre + secciones que se tragan el toque), o
ves algún otro camino por el que el toque no llegue?
