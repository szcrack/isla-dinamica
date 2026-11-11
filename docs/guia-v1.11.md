# Isla Dinámica — Guía de cambios

Proyecto: `com.rayo.isladinamica` (Kotlin, `IslandService`, overlay con `WindowManager`).
Teléfono de prueba: Motorola Edge 2022, Android 15. Cutout de la cámara: Rect 505,0–575,105 px.

> El código está escrito sin ver el proyecto completo (`animateSize()`, `finishSwitch()`,
> `updateExpandedUi()` y los XML no se vieron). Ajusta nombres a tu estructura.

## Orden recomendado

1. Diagnosticar el toque (Toast) y aplicar el arreglo de `toggleExpand()` y del preview.
2. Auto-cierre configurable.
3. Calibración del tamaño exacto de la cámara.
4. Servicio de accesibilidad (detección rápida de app al frente; overlay si el toque lo requiere).
5. Shizuku para ocultar reloj, iconos y notificaciones flotantes del sistema.
6. Filtro de notificaciones duplicadas.
7. Efectos visuales (animación elástica, anillo de carga).

---

## 1. Bug: la isla no responde al toque

### Causas probables (por lectura de código)

- **Callejón sin salida al estar expandida.** `notifSection` y `musicSection` son clickables y
  consumen el toque. Solo `clockSection` y el fondo del root llaman a `toggleExpand()`. Si no hay
  música ni notificación, `openPlayerApp()` y `openCurrentNotif()` no hacen nada, y el long-press
  devuelve `true` y se traga el gesto.
- **`previewPosition()` deja la isla expandida para siempre**, porque no programa auto-colapso.
- **Posible desincronización** entre `isExpanded` y lo visible: `onAnimationEnd` también salta en
  `cancel()`, y `finishSwitch` puede aplicar el objetivo viejo. Si `isExpanded` diverge,
  `expand()` hace early-return y el toque parece no hacer nada.
- **Zona táctil diminuta** (≈27×40 dp, sin padding) pegada al borde superior, donde la barra de
  estado del sistema puede quedarse con el toque.
- Cuando la ventana se vuelve focusable (respuesta en línea) sin `FLAG_NOT_TOUCH_MODAL`, se
  vuelve touch-modal.

### Sobre `layoutInDisplayCutoutMode = ALWAYS` y `privateFlags |= 64`

Ninguno cambia el despacho de toques: el primero afecta al layout y el segundo solo quita la
animación de movimiento. Si usas Y negativa, añade también `FLAG_LAYOUT_NO_LIMITS`.

### Cambios mínimos

```kotlin
private fun toggleExpand() {
    // decide por lo que se ve, no solo por el flag
    if (isExpanded || expandedView.visibility == View.VISIBLE) collapse() else expand()
}

fun previewPosition() {
    val s = instance ?: return
    if (!s.isExpanded) s.expand()
    s.scheduleAutoCollapse(3000) // se reinicia con cada movimiento
}

// Si no hay nada que abrir, el toque colapsa (las funciones deben devolver Boolean)
musicSection?.setOnClickListener { if (!openPlayerApp()) collapse() }
notifSection?.setOnClickListener { if (!openCurrentNotif()) collapse() }
```

En `setWindowFocusable(true)` añade `FLAG_NOT_TOUCH_MODAL`.

En `finishSwitch`, ignora animadores cancelados (no apliques el estado si la animación fue
cancelada).

### Prueba del Toast (decide el camino)

Al inicio de `toggleExpand()`:

```kotlin
Toast.makeText(this, "tap", Toast.LENGTH_SHORT).show()
```

- **Sale "tap":** el toque llega; el fallo está en `expand()` / `updateExpandedUi()` / `animateSize()`.
- **No sale:** el toque no llega a la isla. Sube el deslizador vertical a +40 dp: si ahí sí
  responde, la barra de estado se queda con el toque. Solución: sección 4 (overlay de accesibilidad).

---

## 2. Auto-cierre configurable

```kotlin
private val prefs by lazy { getSharedPreferences("island_prefs", MODE_PRIVATE) }

// 0 = nunca se cierra solo
private fun timeoutMs() = prefs.getLong("popup_timeout_ms", 8000L)

fun scheduleAutoCollapse(delayMs: Long = timeoutMs()) {
    collapseRunnable?.let { handler.removeCallbacks(it) }
    collapseRunnable = null
    if (delayMs <= 0L) return
    collapseRunnable = Runnable {
        if (isExpanded && !isReplyFocused()) collapse()
    }.also { handler.postDelayed(it, delayMs) }
}

private fun isReplyFocused() = replyEditText?.hasFocus() == true
```

En `expand()` llama `scheduleAutoCollapse()` al final (reemplaza el `removeCallbacks` suelto).
En `collapse()` añade al inicio `collapseRunnable?.let { handler.removeCallbacks(it) }`.
Reinicia el contador en los listeners de play/pausa y seekbar con
`if (isExpanded) scheduleAutoCollapse()`.

Deslizador en MainActivity (0–20 s, 0 = nunca):

```kotlin
timeoutSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
    override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
        if (!fromUser) return
        getSharedPreferences("island_prefs", MODE_PRIVATE)
            .edit().putLong("popup_timeout_ms", p * 1000L).apply()
        timeoutLabel.text = if (p == 0) "Nunca" else "$p s"
    }
    override fun onStartTrackingTouch(sb: SeekBar) {}
    override fun onStopTrackingTouch(sb: SeekBar) {}
})
```

---

## 3. Tamaño exacto de la cámara (calibración)

El rect del cutout incluye margen y el agujero real es redondo, así que el tamaño exacto se
calibra a ojo una vez. El rect solo es el valor inicial.

### IslandService

```kotlin
fun aplicarTamañoColapsado() {
    val c = windowManager.currentWindowMetrics.windowInsets.displayCutout
    val r = c?.boundingRectTop ?: Rect(505, 0, 575, 105)
    val w = prefs.getInt("pill_w", r.width())
    val h = prefs.getInt("pill_h", r.width())  // cámara redonda
    collapsedView.layoutParams = collapsedView.layoutParams.apply { width = w; height = h }
    // margen táctil transparente a los lados; la píldora visible no cambia
    val pad = (12 * resources.displayMetrics.density).toInt()
    pillRoot.setPadding(pad, 0, pad, 0)
    params.y = yDelSlider + prefs.getInt("pill_dy", 0)  // suma ambos, no los pises
    windowManager.updateViewLayout(view, params)
}

fun setCalibracion(on: Boolean) {
    if (on && isExpanded) collapse()
    collapsedView.foreground = if (on) ColorDrawable(0x80FF0000.toInt()) else null
}
```

Llama a `aplicarTamañoColapsado()` al final de `setupOverlay()` y dentro de `finishSwitch(false)`.
En `expand()` pon `pillRoot.setPadding(0, 0, 0, 0)` para que el margen táctil solo exista colapsada.

Los 12 dp laterales son transparentes pero bloquean los toques de la barra de estado en esa
franja. Es el costo de poder tocar la isla.

### Layout (MainActivity)

```xml
<LinearLayout
    android:layout_width="match_parent" android:layout_height="wrap_content"
    android:orientation="vertical" android:padding="16dp">

    <TextView android:id="@+id/lblAncho" android:layout_width="wrap_content" android:layout_height="wrap_content"/>
    <SeekBar android:id="@+id/sbAncho" android:layout_width="match_parent" android:layout_height="wrap_content"/>

    <TextView android:id="@+id/lblAlto" android:layout_width="wrap_content" android:layout_height="wrap_content"/>
    <SeekBar android:id="@+id/sbAlto" android:layout_width="match_parent" android:layout_height="wrap_content"/>

    <TextView android:id="@+id/lblY" android:layout_width="wrap_content" android:layout_height="wrap_content"/>
    <SeekBar android:id="@+id/sbY" android:layout_width="match_parent" android:layout_height="wrap_content"/>

    <Button android:id="@+id/btnReset" android:layout_width="wrap_content"
        android:layout_height="wrap_content" android:text="Restablecer"/>
</LinearLayout>
```

### MainActivity

```kotlin
private val prefs by lazy { getSharedPreferences("island_prefs", MODE_PRIVATE) }

private fun configurarCalibracion() {
    val c = windowManager.currentWindowMetrics.windowInsets.displayCutout
    val r = c?.boundingRectTop ?: Rect(505, 0, 575, 105)
    slider(sbAncho, lblAncho, "pill_w", 20, 200, r.width(), "Ancho")
    slider(sbAlto, lblAlto, "pill_h", 20, 200, r.width(), "Alto")
    slider(sbY, lblY, "pill_dy", -60, 60, 0, "Posición Y")
    btnReset.setOnClickListener {
        prefs.edit().remove("pill_w").remove("pill_h").remove("pill_dy").apply()
        configurarCalibracion()
        IslandService.instance?.aplicarTamañoColapsado()
    }
}

private fun slider(sb: SeekBar, lbl: TextView, key: String,
                   min: Int, max: Int, def: Int, nombre: String) {
    sb.max = max - min
    val actual = prefs.getInt(key, def)
    sb.progress = actual - min
    lbl.text = "$nombre: $actual px"
    sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
            val v = p + min
            lbl.text = "$nombre: $v px"
            if (!fromUser) return
            prefs.edit().putInt(key, v).apply()
            IslandService.instance?.aplicarTamañoColapsado()
        }
        override fun onStartTrackingTouch(s: SeekBar) {
            IslandService.instance?.setCalibracion(true)
        }
        override fun onStopTrackingTouch(s: SeekBar) {
            IslandService.instance?.setCalibracion(false)
        }
    })
}
```

Llama a `configurarCalibracion()` desde `onCreate()`.

### Cómo calibrar

1. Mueve Ancho hasta que el rojo cubra el borde del agujero en horizontal.
2. Haz lo mismo con Alto.
3. Ajusta Posición Y para centrarlo.
4. Suelta el deslizador: vuelve a negro. Si queda un borde de color, reduce 1–2 px.

No hace falta desplazamiento horizontal: la cámara está centrada (centro en 540 de 1080) y la
ventana va con `CENTER_HORIZONTAL`.

---

## 4. Servicio de accesibilidad

Sirve para dos cosas: detectar al instante qué app está al frente (reemplaza el polling de 2 s)
y, si el toque no llega por la barra de estado, dibujar la isla con `TYPE_ACCESSIBILITY_OVERLAY`,
que va por encima de la barra de estado. Solo se lee el nombre del paquete.

### `res/xml/accesibilidad.xml`

```xml
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeWindowStateChanged|typeWindowsChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:canRetrieveWindowContent="true"
    android:notificationTimeout="0" />
```

### Manifest

```xml
<service android:name=".FrenteService"
    android:exported="false"
    android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
    <intent-filter>
        <action android:name="android.accessibilityservice.AccessibilityService" />
    </intent-filter>
    <meta-data android:name="android.accessibilityservice"
        android:resource="@xml/accesibilidad" />
</service>
```

### FrenteService

```kotlin
class FrenteService : AccessibilityService() {
    companion object { var instance: FrenteService? = null }

    override fun onServiceConnected() {
        instance = this
        IslandService.instance?.recrearOverlay()
    }

    override fun onUnbind(i: Intent?): Boolean {
        instance = null
        IslandService.instance?.recrearOverlay()
        return super.onUnbind(i)
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        // si no hay ventana activa todavía, ignora el evento
        val pkg = rootInActiveWindow?.packageName?.toString() ?: return
        IslandService.instance?.onAppAlFrente(pkg)
    }

    override fun onInterrupt() {}
}
```

### IslandService (solo si usas el overlay de accesibilidad)

```kotlin
private var overlayWm: WindowManager? = null

private fun tipoOverlay() =
    if (FrenteService.instance != null)
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
    else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

fun recrearOverlay() {
    runCatching { overlayWm?.removeView(view) }
    setupOverlay()
}
```

Dentro de `setupOverlay()`:

- Usa `tipoOverlay()` como tercer argumento de `LayoutParams`.
- Antes de añadir la vista:
  `overlayWm = (FrenteService.instance ?: this).getSystemService(WINDOW_SERVICE) as WindowManager`
- Cambia `windowManager.addView(...)` por `overlayWm!!.addView(...)`.
- Todos los `updateViewLayout` y `removeView` de la isla (animación, calibrador) deben usar el
  mismo `WindowManager`, si no fallan.

### Activarlo

Ajustes → Accesibilidad → Apps descargadas → tu app. En Android 13+ puede estar bloqueado por
"ajustes restringidos": Info de la app → ⋮ → "Permitir ajustes restringidos".

No lo actives con `settings put secure enabled_accessibility_services` desde Shizuku: desactivaría
cualquier otro servicio de accesibilidad que tengas.

---

## 5. Ocultar reloj, iconos y notificaciones flotantes con Shizuku

Se usa `cmd statusbar send-disable-flag`, ejecutado por un `UserService` de Shizuku.

Comportamiento:

- **En el launcher:** se ocultan reloj e iconos de notificación.
- **En cualquier lado:** se ocultan las notificaciones flotantes del sistema (la isla las muestra).
- **Panel de ajustes rápidos:** el reloj debe verse. El flag `clock` es global y lo oculta también
  en el panel, así que hay que restaurarlo al abrirlo. Para eso sirve el servicio de la sección 4:
  el panel pertenece a `com.android.systemui`, que se trata como "no launcher".

### Dependencias

```gradle
implementation "dev.rikka.shizuku:api:13.1.5"
implementation "dev.rikka.shizuku:provider:13.1.5"
```

### Manifest

```xml
<provider
    android:name="rikka.shizuku.ShizukuProvider"
    android:authorities="${applicationId}.shizuku"
    android:exported="true"
    android:multiprocess="false"
    android:permission="android.permission.INTERACT_ACROSS_USERS_FULL" />
```

### `src/main/aidl/com/rayo/isladinamica/IShell.aidl`

```
package com.rayo.isladinamica;
interface IShell { void run(String cmd); }
```

### Servicio con permisos de shell

```kotlin
class ShellService : IShell.Stub() {
    override fun run(cmd: String) {
        Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd)).waitFor()
    }
}
```

### Controlador

```kotlin
object BarraShizuku {
    private var shell: IShell? = null
    private val args = Shizuku.UserServiceArgs(
        ComponentName(BuildConfig.APPLICATION_ID, ShellService::class.java.name)
    ).daemon(false).processNameSuffix("shell").version(1)

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName, b: IBinder) {
            shell = IShell.Stub.asInterface(b)
        }
        override fun onServiceDisconnected(n: ComponentName) { shell = null }
    }

    fun bind() {
        if (Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        ) Shizuku.bindUserService(args, conn)
    }

    // Añade system-icons si también quieres ocultar wifi/batería
    fun aplicar(enLauncher: Boolean) {
        val flags = if (enLauncher) "clock notification-icons notification-peek"
                    else "notification-peek"
        shell?.run("cmd statusbar send-disable-flag $flags")
    }

    fun restaurar() { shell?.run("cmd statusbar send-disable-flag none") }
}
```

### Detección del launcher (IslandService)

```kotlin
private var enLauncherAntes: Boolean? = null

private fun launcherPkg(): String? =
    packageManager.resolveActivity(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
        PackageManager.MATCH_DEFAULT_ONLY
    )?.activityInfo?.packageName

fun onAppAlFrente(pkg: String) {
    val enLauncher = pkg == launcherPkg()
    if (enLauncher != enLauncherAntes) {
        enLauncherAntes = enLauncher
        BarraShizuku.aplicar(enLauncher)
    }
}
```

Llama a `BarraShizuku.bind()` en `onCreate()`.

### Seguro anti-barra-atascada (importante)

Los flags se quedan activos hasta que alguien los limpie. Si el servicio muere con la barra
oculta, te quedas sin reloj en todo el sistema.

- Llama `BarraShizuku.restaurar()` en `onDestroy()` y cuando la pantalla se apague.
- Llámalo también al abrir MainActivity.
- Añade un botón "Restaurar barra" en la app.

### Notas

- Shizuku sin root hay que reiniciarlo tras cada reinicio del teléfono (requiere depuración
  inalámbrica). Sin Shizuku activo, la barra queda normal.
- Los nombres de flags pueden variar por versión de Android. Confirma con `cmd statusbar help`.

---

## 6. Notificaciones duplicadas

Hay dos fuentes de duplicado: la flotante del sistema (se resuelve con el flag
`notification-peek` de la sección 5) y WhatsApp, que publica varias notificaciones por mensaje
(mensaje, resumen del grupo, actualizaciones).

```kotlin
private val vistas = HashMap<String, Long>()

override fun onNotificationPosted(sbn: StatusBarNotification) {
    val n = sbn.notification
    if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
    val clave = sbn.key + (n.extras.getCharSequence(Notification.EXTRA_TEXT) ?: "")
    val ahora = SystemClock.elapsedRealtime()
    if (ahora - (vistas[clave] ?: 0L) < 2000) return   // repetida
    vistas[clave] = ahora
    // ...tu lógica actual
}
```

---

## 7. Efectos al estilo HyperOS

No se copia código de ningún tercero: son implementaciones propias con APIs públicas.

### 7.1 Animación elástica

En `animateSize()`, antes de `start()`:

```kotlin
animator.duration = 380
animator.interpolator = PathInterpolator(0.2f, 1.25f, 0.3f, 1f) // rebota un poco al llegar
```

Sube 1.25 para más rebote; con 1.0 no rebota.

### 7.2 Anillo de carga alrededor de la cámara

Va en una ventana aparte **no tocable**, así que no interfiere con el toque de la isla.

```kotlin
class AnilloView(ctx: Context) : View(ctx) {
    private val trazo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 6f
    }
    var color = 0xFF34C759.toInt()
    var progreso = 0f
        set(v) { field = v; invalidate() }

    override fun onDraw(c: Canvas) {
        val m = trazo.strokeWidth / 2
        val r = RectF(m, m, width - m, height - m)
        trazo.color = color
        trazo.alpha = 50
        c.drawArc(r, 0f, 360f, false, trazo)          // pista tenue
        trazo.alpha = 255
        c.drawArc(r, -90f, 360f * progreso, false, trazo)
    }
}
```

En `IslandService`:

```kotlin
private var anillo: AnilloView? = null
private val ocultarAnillo = Runnable {
    anillo?.let { if (it.isAttachedToWindow) windowManager.removeView(it) }
}

private fun mostrarAnillo(nivel: Int, cargando: Boolean) {
    val pad = (8 * resources.displayMetrics.density).toInt()
    val w = prefs.getInt("pill_w", 70) + 2 * pad
    val h = prefs.getInt("pill_h", 70) + 2 * pad
    val v = anillo ?: AnilloView(this).also { anillo = it }
    v.color = if (!cargando && nivel <= 15) 0xFFFF3B30.toInt() else 0xFF34C759.toInt()

    val lp = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        y = params.y - pad          // mismo Y que la isla, centrado en la cámara
        layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }
    if (v.isAttachedToWindow) windowManager.updateViewLayout(v, lp)
    else windowManager.addView(v, lp)

    ObjectAnimator.ofFloat(v, "progreso", 0f, nivel / 100f).setDuration(900).start()
    handler.removeCallbacks(ocultarAnillo)
    handler.postDelayed(ocultarAnillo, 3500)
}

private val receptorBateria = object : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val b = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val nivel = b.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
        mostrarAnillo(nivel, i.action == Intent.ACTION_POWER_CONNECTED)
    }
}
```

En `onCreate()`:

```kotlin
ContextCompat.registerReceiver(
    this, receptorBateria,
    IntentFilter().apply {
        addAction(Intent.ACTION_POWER_CONNECTED)
        addAction(Intent.ACTION_POWER_DISCONNECTED)
    },
    ContextCompat.RECEIVER_NOT_EXPORTED
)
```

En `onDestroy()`: `unregisterReceiver(receptorBateria)` y `ocultarAnillo.run()`.

Si usas el overlay de accesibilidad (sección 4), este anillo también debería usar ese
`WindowManager` y `TYPE_ACCESSIBILITY_OVERLAY`, para que quede a la misma altura que la isla.

### 7.3 Ideas pendientes

- **Pulso luminoso** al llegar una notificación (borde que respira).
- **Color adaptativo** según la carátula del álbum (`Palette`).
- **Visualizador de música** con barras animadas en la píldora colapsada.
- **Burbujas duales**: música a un lado y temporizador o llamada al otro.
- **Anillo de progreso** para descargas y temporizadores (reutiliza `AnilloView`).

---

## 8. Migración desde DynamicSpot (solo conceptos, sin su código)

Orden sugerido: timeout de auto-cierre → long-press ajustable (con `GestureDetector`) →
tamaño y ancho del popup → deslizar para descartar → lockscreen y forma que sigue al notch →
visualizador de música.
