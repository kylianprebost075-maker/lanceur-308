package fr.p308.lanceur.ui

import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.filament.Colors
import com.google.android.filament.Engine
import com.google.android.filament.LightManager
import com.google.android.filament.View
import io.github.sceneview.SceneView
import io.github.sceneview.environment.Environment
import io.github.sceneview.loaders.EnvironmentLoader
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.node.CameraNode
import io.github.sceneview.node.LightNode
import io.github.sceneview.node.Node
import io.github.sceneview.rememberCollisionSystem
import io.github.sceneview.rememberOnGestureListener
import io.github.sceneview.rememberRenderer
import io.github.sceneview.rememberScene

/** Cadence cible pendant le roulage ou l'ouverture d'une porte : 28 images/s soulagent le GPU Mali-T820. */
private const val FRAME_INTERVAL_NS = 1_000_000_000L / 28

/**
 * SceneView optimisée avec rendu à la demande (0 image/s au repos) :
 * - À l'arrêt, quand aucune porte ni animation ne bouge, on saute complètement le rendu OpenGL (0 % GPU).
 * - En roulant ou pendant l'ouverture d'un ouvrant : 28 images/s fluides et régulières.
 * - Doigt posé sur l'écran ou plongée caméra vers l'habitacle : pleine cadence.
 */
private class ThrottledSceneView(
    activity: ComponentActivity?, engine: Engine, modelLoader: ModelLoader, materialLoader: MaterialLoader,
    environmentLoader: EnvironmentLoader, scene: com.google.android.filament.Scene, view: View,
    renderer: com.google.android.filament.Renderer, camera: CameraNode, light: LightNode, environment: Environment,
    collisionSystem: io.github.sceneview.collision.CollisionSystem,
    manipulator: com.google.android.filament.utils.Manipulator?, lifecycle: androidx.lifecycle.Lifecycle,
    context: android.content.Context,
) : SceneView(
    context, null, 0, 0, engine, modelLoader, materialLoader, environmentLoader, scene, view, renderer,
    camera, light, environment, false, collisionSystem, manipulator, null, null, null, activity, lifecycle,
) {
    private var lastRendered = 0L
    private var lastChecked = 0L
    private var warmupFrames = 20
    private var settleFrames = 2
    var fullFps = false
    var touching = false
    var rawTouch: ((MotionEvent) -> Boolean)? = null
    var stepScene: ((Long) -> Boolean)? = null

    fun requestWakeup(frames: Int = 8) {
        if (frames > warmupFrames) warmupFrames = frames
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestWakeup(20)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        requestWakeup(16)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) requestWakeup(20)
    }

    override fun onFrame(frameTimeNanos: Long) {
        if (!touching && !fullFps && warmupFrames <= 0 && frameTimeNanos - lastChecked < FRAME_INTERVAL_NS - 2_000_000L) {
            return
        }
        lastChecked = frameTimeNanos
        val active = stepScene?.invoke(frameTimeNanos) ?: true
        if (active) {
            settleFrames = 2
        } else if (!touching && warmupFrames <= 0) {
            if (settleFrames <= 0) return
            settleFrames--
        }
        if (warmupFrames > 0) warmupFrames--
        lastRendered = frameTimeNanos
        super.onFrame(frameTimeNanos)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                touching = true
                requestWakeup(8)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touching = false
                requestWakeup(18)
            }
        }
        // En vue habitacle, on intercepte directement le glisser sans lancer le raycast 3D sur 60 000 triangles.
        rawTouch?.let { return it(event) }
        return super.onTouchEvent(event)
    }
}

/** Lumière directionnelle studio sans shadow-map temps réel (évite une double passe géométrique sur Mali-T820). */
@Composable
fun rememberStudioLight(engine: Engine): LightNode {
    val light = remember(engine) {
        val c = Colors.cct(6_300f)
        LightNode(engine, LightManager.Type.DIRECTIONAL) {
            color(c[0], c[1], c[2])
            intensity(SceneView.DEFAULT_MAIN_LIGHT_COLOR_INTENSITY)
            direction(-0.22f, -1f, -0.32f)
            castShadows(false)
        }
    }
    DisposableEffect(light) { onDispose { light.destroy() } }
    return light
}

/** Scène 3D transparente avec rendu à la demande (0 FPS au repos). */
@Composable
fun LightScene(
    modifier: Modifier,
    engine: Engine,
    modelLoader: ModelLoader,
    materialLoader: MaterialLoader,
    environmentLoader: EnvironmentLoader,
    view: View,
    environment: Environment,
    cameraNode: CameraNode,
    childNodes: List<Node>,
    cameraManipulator: com.google.android.filament.utils.Manipulator?,
    sunIntensity: Float = SceneView.DEFAULT_MAIN_LIGHT_COLOR_INTENSITY,
    fullFps: Boolean = false,
    wakeupKey: Any? = null,
    rawTouch: ((MotionEvent) -> Boolean)? = null,
    stepScene: (Long) -> Boolean,
) {
    val renderer = rememberRenderer(engine)
    val scene = rememberScene(engine)
    val light = rememberStudioLight(engine)
    val collisionSystem = rememberCollisionSystem(view)
    val gestures = rememberOnGestureListener()
    val activity = LocalContext.current as? ComponentActivity
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lastWakeup = remember { arrayOf<Any?>(null) }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            ThrottledSceneView(
                activity, engine, modelLoader, materialLoader, environmentLoader, scene, view, renderer,
                cameraNode, light, environment, collisionSystem, cameraManipulator, lifecycle, context,
            )
        },
        update = { sv ->
            sv.fullFps = fullFps
            if (light.intensity != sunIntensity) {
                light.intensity = sunIntensity
                sv.requestWakeup(6)
            }
            if (sv.environment !== environment) {
                sv.environment = environment
                sv.requestWakeup(12)
            }
            sv.setCameraNode(cameraNode)
            if (sv.childNodes != childNodes) {
                sv.childNodes = childNodes
                sv.requestWakeup(20)
            }
            if (sv.cameraManipulator !== cameraManipulator) {
                sv.cameraManipulator = cameraManipulator
                sv.requestWakeup(8)
            }
            if (lastWakeup[0] != wakeupKey) {
                lastWakeup[0] = wakeupKey
                sv.requestWakeup(8)
            }
            sv.onGestureListener = gestures
            sv.rawTouch = rawTouch
            sv.stepScene = stepScene
        },
        onReset = {},
        onRelease = { it.destroy() },
    )
}
