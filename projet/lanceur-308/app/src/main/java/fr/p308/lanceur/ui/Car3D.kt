package fr.p308.lanceur.ui

import android.content.Context
import android.net.Uri
import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.google.android.filament.LightManager
import com.google.android.filament.MaterialInstance
import com.google.android.filament.View
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.normalize
import fr.p308.lanceur.drive.Trip
import fr.p308.lanceur.vehicle.Opening
import io.github.sceneview.SceneView
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.node.CubeNode
import io.github.sceneview.node.CylinderNode
import io.github.sceneview.node.LightNode
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironment
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberNode
import io.github.sceneview.rememberView
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * Modèle 3D de la voiture : d'abord un car.glb déposé dans
 * /sdcard/Android/data/fr.p308.lanceur/files/ (remplaçable sans recompiler), sinon celui embarqué.
 */
object CarModel {
    private const val FILE_NAME = "car.glb"

    fun location(ctx: Context): String? {
        val pushed = File(ctx.getExternalFilesDir(null), FILE_NAME)
        if (pushed.exists()) return Uri.fromFile(pushed).toString()
        // v0.23 : le modèle embarqué, avec la plaque tapée dans les Réglages.
        PlateModel.location(ctx)?.let { return it }
        if (ctx.assets.list("models")?.contains(FILE_NAME) == true) return "models/$FILE_NAME"
        return null
    }
}

/** Permet aux boutons ‹ › de l'en-tête de faire pivoter la voiture par pas de 45°. */
class CarSpinController {
    var version by mutableIntStateOf(0)
        private set
    var lastBy = 0f
        private set

    fun turn(by: Float) {
        lastBy = by
        version++
    }
}

/**
 * Voiture en 3D façon Tesla, hautement optimisée pour l'autoradio UIS8141E / Mali-T820 :
 * - À l'arrêt : voiture immobile à -28° sur son plateau studio, rendu coupé à 0 FPS dès qu'elle est posée.
 * - En roulant : le plateau disparaît au profit d'une voie animée, les 4 jantes tournent vers l'avant.
 * - En Entretien (interior = true) : plongée fluide à travers le pare-brise jusqu'au siège conducteur.
 */
@Composable
fun Car3D(
    location: String,
    interior: Boolean,
    open: Set<Opening>,
    headlights: Boolean,
    fogLights: Boolean,
    reverse: Boolean = false,
    spinController: CarSpinController? = null,
    modifier: Modifier = Modifier,
    onFail: () -> Unit,
) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)

    // Configuration ultra-rapide pour Mali-T820 MP1 : pas de shadow-map, pas de Bloom multi-passes,
    // résolution dynamique 68..82 % pour diviser par ~2 le nombre de pixels calculés en 3D.
    val view = rememberView(engine) {
        SceneView.createView(engine).apply {
            setShadowingEnabled(false)
            setPostProcessingEnabled(true)
            // FXAA : lissage des bords en escalier pour un coût quasi nul (1 passe plein écran).
            antiAliasing = View.AntiAliasing.FXAA
            dithering = View.Dithering.TEMPORAL
            bloomOptions = View.BloomOptions().apply { enabled = false }
            renderQuality = View.RenderQuality().apply {
                hdrColorBuffer = View.QualityLevel.MEDIUM
            }
            // À l'arrêt (0 FPS), l'image est calculée en pleine définition ; voir applyRenderScale().
            dynamicResolutionOptions = lowResOptions()
        }
    }
    // true = pleine définition (voiture immobile), false = 75 % pendant les animations pour rester fluide.
    val hiRes = remember { booleanArrayOf(false) }
    val camera = rememberCameraNode(engine) {
        focalLength = EXTERIOR_FOCAL
        // Plans de coupe adaptés : à 1 cm (valeur par défaut) le tampon de profondeur du Mali-T820 manque
        // de précision et l'habitacle « scintille » à travers la carrosserie et le pare-brise en vue extérieure.
        near = EXTERIOR_NEAR
        far = CAMERA_FAR
        position = CAMERA
        lookAt(TARGET)
    }
    val env = rememberEnvironment(environmentLoader, isOpaque = false) {
        SceneView.createEnvironment(environmentLoader, isOpaque = false).also {
            it.indirectLight?.intensity = 13_500f
        }
    }
    val pivot = rememberNode(engine)
    var nodes by remember { mutableStateOf(emptyList<Node>()) }
    var car by remember { mutableStateOf<ModelNode?>(null) }
    val look = remember { FloatArray(2) } // lacet, tangage (degrés) de la tête du conducteur
    val spin = remember { floatArrayOf(HERO_ANGLE, HERO_ANGLE) } // angle courant, angle visé
    val mix = remember { floatArrayOf(if (interior) 1f else 0f) } // 0 = extérieur, 1 = habitacle
    val extStartPos = remember { arrayOf(CAMERA) }
    var settledExterior by remember { mutableStateOf(!interior) }
    var transitioning by remember { mutableStateOf(false) }
    val parts = remember { CarParts() }

    LaunchedEffect(location) {
        val instance = runCatching { modelLoader.loadModelInstance(location) }.getOrNull()
        if (instance == null) {
            onFail()
            return@LaunchedEffect
        }
        val node = ModelNode(instance, autoAnimate = false, scaleToUnits = 3.25f)
        val basePos = -(node.center * node.scale)
        node.position = basePos
        node.isShadowCaster = false
        node.isShadowReceiver = false
        pivot.addChildNode(node)

        parts.carNode = node
        parts.baseCarPos = basePos
        parts.hinges = HINGES.mapNotNull { (opening, hinge) ->
            node.emptyNodes.firstOrNull { it.name == hinge.node }?.let { opening to it }
        }.toMap()

        // Moyeux des 4 roues (rotation avant : +X à gauche, -X à droite car retournées de 180° autour de Z).
        parts.leftWheels = listOfNotNull(
            node.emptyNodes.firstOrNull { it.name == "moyeu_AVG" } ?: node.emptyNodes.firstOrNull { it.name == "roue_AVG" },
            node.emptyNodes.firstOrNull { it.name == "moyeu_ARG" } ?: node.emptyNodes.firstOrNull { it.name == "roue_ARG" },
        )
        parts.rightWheels = listOfNotNull(
            node.emptyNodes.firstOrNull { it.name == "moyeu_AVD" },
            node.emptyNodes.firstOrNull { it.name == "moyeu_ARD" },
        )

        // Masquage automatique des petits détails d'habitacle (aiguilles, voyants, façade console = 18 draw calls)
        // tant que la caméra est à l'extérieur.
        val tm = engine.transformManager
        val detailParents = node.emptyNodes
            .filter { it.name in INTERIOR_DETAIL_NODES }
            .map { tm.getInstance(it.entity) }
            .toSet()
        parts.interiorDetailMeshes = node.renderableNodes.filter { rn ->
            var p = tm.getParent(tm.getInstance(rn.entity))
            var found = false
            while (p != 0) {
                if (tm.getInstance(p) in detailParents) {
                    found = true
                    break
                }
                p = tm.getParent(tm.getInstance(p))
            }
            found
        }
        parts.setInteriorDetailsVisible(interior)

        val allMats = node.renderableNodes.flatMap { it.materialInstances }
        parts.headlightMaterials = allMats.filter { it.name == "phare_avant" }
        parts.headlightReflectorMaterials = allMats.filter { it.name == "phare_reflecteur" }
        parts.headlightGlassMaterials = allMats.filter { it.name == "vitre_phare_avant" }
        parts.dashBeamMaterials = allMats.filter { it.name == "voyant_feux" }
        parts.taillightMaterials = allMats.filter { it.name == "feu_position_ar" }
        parts.reverseMaterials = allMats.filter { it.name == "feu_blanc" }
        parts.fogMaterials = allMats.filter { it.name == "antibrouillard" }

        // Projecteurs détachés de la scène quand ils sont éteints (0 lumière ponctuelle évaluée le jour !).
        parts.headlightSpots = HEAD_LAMPS.map { lamp ->
            LightNode(engine, LightManager.Type.FOCUSED_SPOT) {
                color(1f, 0.96f, 0.88f)
                intensity(0f)
                falloff(5.5f)
                spotLightCone(0.32f, 0.78f)
                direction(0f, -0.95f, 1f)
                castShadows(false)
            }.also { it.position = lamp }
        }
        parts.fogSpots = FOG_LAMPS.map { lamp ->
            LightNode(engine, LightManager.Type.FOCUSED_SPOT) {
                color(1f, 0.92f, 0.76f)
                intensity(0f)
                falloff(4.5f)
                spotLightCone(0.35f, 0.80f)
                direction(0f, -0.85f, 1f)
                castShadows(false)
            }.also { it.position = lamp }
        }

        val ground = -node.halfExtent.y * node.scale.y
        parts.groundY = ground

        // Ombre douce ovale sous le châssis (1 seul draw call au lieu d'une passe shadow-map de 60 000 triangles).
        val shadow = CylinderNode(
            engine,
            radius = 1.0f,
            height = 0.004f,
            center = Position(y = ground + 0.002f),
            sideCount = 32,
            materialInstance = materialLoader.createColorInstance(Color(0xFF020409), metallic = 0f, roughness = 1f),
        ).apply {
            scale = Scale(0.76f, 1f, 1.62f)
            isShadowCaster = false
            isShadowReceiver = false
        }
        pivot.addChildNode(shadow)

        // Voie routière animée (visible uniquement quand la voiture roule).
        val asphaltMat = materialLoader.createColorInstance(Color(0xFF070B14), metallic = 0f, roughness = 0.65f)
        val railMat = materialLoader.createColorInstance(Color(0xFF2E63D8), metallic = 0f, roughness = 0.35f)
        val dashMat = materialLoader.createColorInstance(Color(0xFFDCE6FF), metallic = 0f, roughness = 0.25f)

        val asphalt = CubeNode(
            engine,
            size = Float3(2.14f, 0.004f, 6.8f),
            center = Position(0f, ground - 0.012f, -0.2f),
            materialInstance = asphaltMat,
        )
        val railL = CubeNode(
            engine,
            size = Float3(0.024f, 0.004f, 6.8f),
            center = Position(1.09f, ground - 0.008f, -0.2f),
            materialInstance = railMat,
        )
        val railR = CubeNode(
            engine,
            size = Float3(0.024f, 0.004f, 6.8f),
            center = Position(-1.09f, ground - 0.008f, -0.2f),
            materialInstance = railMat,
        )
        val dashPairs = List(DASH_COUNT) {
            val dl = CubeNode(engine, size = Float3(0.055f, 0.005f, 0.50f), center = Position(), materialInstance = dashMat)
            val dr = CubeNode(engine, size = Float3(0.055f, 0.005f, 0.50f), center = Position(), materialInstance = dashMat)
            dl to dr
        }
        val allRoadNodes = buildList {
            add(asphalt)
            add(railL)
            add(railR)
            dashPairs.forEach { (l, r) -> add(l); add(r) }
        }
        allRoadNodes.forEach { rn ->
            rn.isShadowCaster = false
            rn.isShadowReceiver = false
            rn.isVisible = false
            pivot.addChildNode(rn)
        }
        parts.dashPairs = dashPairs
        parts.roadRenderables = allRoadNodes

        // Plateau studio (visible à l'arrêt, masqué dès que la voiture roule).
        val rim = CylinderNode(
            engine,
            radius = 2.42f,
            height = 0.02f,
            center = Position(y = ground - 0.016f),
            sideCount = 48,
            materialInstance = materialLoader.createColorInstance(Color(0xFF59627E), metallic = 0.5f, roughness = 0.35f),
        ).apply { isShadowCaster = false; isShadowReceiver = false }
        val disc = CylinderNode(
            engine,
            radius = 2.35f,
            height = 0.02f,
            center = Position(y = ground - 0.01f),
            sideCount = 48,
            materialInstance = materialLoader.createColorInstance(Color(0xFF0B0E17), metallic = 0f, roughness = 0.50f),
        ).apply { isShadowCaster = false; isShadowReceiver = false }

        parts.turntableNodes = listOf(rim, disc)
        car = node
        pivot.rotation = Rotation(y = spin[0])
        nodes = listOf(rim, disc, pivot)
    }

    fun toWorld(p: Position): Position = car!!.let { (p - it.center) * it.scale }
    fun seatLookTarget(): Position {
        val eye = toWorld(EYE)
        val ahead = normalize(toWorld(LOOK_AHEAD) - eye)
        val yaw = Math.toRadians(look[0].toDouble())
        val pitch = Math.toRadians(look[1].toDouble())
        val dir = Float3(
            (ahead.x * cos(yaw) + ahead.z * sin(yaw)).toFloat(),
            (ahead.y + sin(pitch)).toFloat(),
            (-ahead.x * sin(yaw) + ahead.z * cos(yaw)).toFloat(),
        )
        return eye + dir
    }

    fun applySeatCamera() {
        val eye = toWorld(EYE)
        camera.position = eye
        camera.lookAt(seatLookTarget())
    }

    fun shortestSpinTo(targetDeg: Float): Float =
        spin[0] + ((targetDeg - spin[0]) % 360f + 540f) % 360f - 180f

    // Plongée fluide type Tesla entre la vue studio extérieure et le poste de conduite (Entretien).
    LaunchedEffect(interior, car) {
        if (car == null) return@LaunchedEffect
        if (interior) {
            settledExterior = false
            transitioning = true
            look[0] = 0f
            look[1] = 0f
            extStartPos[0] = if (mix[0] < 0.05f) camera.position else CAMERA
            spin[1] = shortestSpinTo(0f)
            if (mix[0] >= 0.999f) {
                parts.setInteriorDetailsVisible(true)
                camera.focalLength = INTERIOR_FOCAL
                camera.near = INTERIOR_NEAR
                applySeatCamera()
                env.indirectLight?.intensity = 28_000f
                transitioning = false
            }
        } else {
            extStartPos[0] = CAMERA
            if (mix[0] > 0.001f) {
                settledExterior = false
                transitioning = true
                spin[1] = shortestSpinTo(HERO_ANGLE)
            } else {
                parts.setInteriorDetailsVisible(false)
                settledExterior = true
                transitioning = false
                camera.focalLength = EXTERIOR_FOCAL
                camera.near = EXTERIOR_NEAR
                camera.position = CAMERA
                camera.lookAt(TARGET)
                env.indirectLight?.intensity = 13_500f
            }
        }
    }

    LaunchedEffect(spinController?.version) {
        val ctrl = spinController ?: return@LaunchedEffect
        if (ctrl.version == 0 || interior) return@LaunchedEffect
        spin[1] += ctrl.lastBy
    }

    val manipulator = remember(settledExterior) {
        if (!settledExterior) null else SceneView.createDefaultCameraManipulator(CAMERA, TARGET)
    }
    var lastX by remember { mutableFloatStateOf(0f) }
    var lastY by remember { mutableFloatStateOf(0f) }
    var lastFrame by remember { mutableLongStateOf(0L) }

    // Quand un ouvrant s'ouvre ou que la marche arrière s'engage, on oriente la voiture pour le montrer ;
    // quand tout se referme, elle revient à sa posture 3/4 (-28°) et reste parfaitement immobile.
    LaunchedEffect(open, headlights, fogLights, reverse, interior) {
        val added = open - parts.lastOpen
        val hadOpenOrRev = parts.lastOpen.isNotEmpty() || parts.lastReverse
        val focus = added.firstOrNull()?.let { FOCUS.getValue(it) }
            ?: if (reverse && !parts.lastReverse) 155f
            else if (open.isEmpty() && !reverse && hadOpenOrRev) HERO_ANGLE
            else null
        parts.lastOpen = open
        parts.lastHeadlights = headlights
        parts.lastFog = fogLights
        parts.lastReverse = reverse
        if (interior || !settledExterior) return@LaunchedEffect
        if (focus != null) {
            spin[1] = shortestSpinTo(focus)
        }
    }

    val wakeupKey = remember(interior, open, headlights, fogLights, reverse, spinController?.version) {
        listOf(interior, open, headlights, fogLights, reverse, spinController?.version)
    }

    Box(modifier.fillMaxSize()) {
        // Halo « studio » statique derrière la voiture (dessiné 1 seule fois, 0 charge GPU).
        Canvas(Modifier.matchParentSize()) {
            drawRect(Brush.radialGradient(
                listOf(Color(0xFF3A4568).copy(alpha = 0.56f), Color.Transparent),
                center = Offset(size.width / 2, size.height * 0.54f), radius = size.minDimension * 0.82f,
            ))
        }
        LightScene(
            modifier = Modifier.matchParentSize(),
            engine = engine,
            view = view,
            modelLoader = modelLoader,
            materialLoader = materialLoader,
            environmentLoader = environmentLoader,
            environment = env,
            cameraNode = camera,
            childNodes = nodes,
            cameraManipulator = manipulator,
            sunIntensity = if (!interior && (headlights || fogLights)) 42_000f else SceneView.DEFAULT_MAIN_LIGHT_COLOR_INTENSITY,
            fullFps = transitioning,
            wakeupKey = wakeupKey,
            rawTouch = if (!interior) null else { e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { lastX = e.x; lastY = e.y }
                    MotionEvent.ACTION_MOVE -> {
                        look[0] = (look[0] + (e.x - lastX) * 0.25f).coerceIn(-150f, 150f)
                        look[1] = (look[1] - (e.y - lastY) * 0.15f).coerceIn(-35f, 35f)
                        lastX = e.x
                        lastY = e.y
                        if (car != null && !transitioning) applySeatCamera()
                    }
                }
                true
            },
            stepScene = { nanos ->
                if (car == null) {
                    lastFrame = nanos
                    false
                } else {
                    val rawDt = if (lastFrame == 0L) 0.016f else (nanos - lastFrame) / 1e9f
                    lastFrame = nanos
                    // Si le moteur 3D dormait (0 FPS au repos), le premier pas repart proprement à 16 ms.
                    val dt = if (rawDt > 0.12f || rawDt <= 0f) 0.016f else rawDt

                    var changed = false
                    val speedKmh = Trip.state.value.currentKmh
                    val driving = !interior && settledExterior && speedKmh >= DRIVE_THRESHOLD_KMH

                    // Si la voiture commence à rouler, on la remet dans l'axe 3/4 de conduite.
                    if (driving && !parts.wasDriving && open.isEmpty() && !reverse) {
                        spin[1] = shortestSpinTo(HERO_ANGLE)
                    }
                    parts.wasDriving = driving

                    // Rotation uniquement vers l'angle demandé (aucune rotation continue à l'arrêt !).
                    val spinDiff = spin[1] - spin[0]
                    if (abs(spinDiff) > 0.04f) {
                        val rate = if (!interior && settledExterior) 6.5f else 9.0f
                        spin[0] += spinDiff * (1f - exp(-rate * dt))
                        pivot.rotation = Rotation(y = spin[0] % 360f)
                        changed = true
                    } else if (spinDiff != 0f) {
                        spin[0] = spin[1]
                        pivot.rotation = Rotation(y = spin[0] % 360f)
                        changed = true
                    }

                    // Transition caméra extérieur <-> habitacle.
                    val targetMix = if (interior) 1f else 0f
                    if (abs(mix[0] - targetMix) > 1e-4f) {
                        changed = true
                        val step = dt / 0.72f
                        mix[0] = if (targetMix > mix[0]) (mix[0] + step).coerceAtMost(1f) else (mix[0] - step).coerceAtLeast(0f)
                        parts.setInteriorDetailsVisible(mix[0] >= 0.14f)
                        val s = mix[0] * mix[0] * (3f - 2f * mix[0])
                        val p0 = extStartPos[0]
                        val p2 = toWorld(EYE)
                        val ctrl = Position(p2.x * 0.7f, 0.52f, 0.85f)
                        val u = 1f - s
                        camera.position = Position(
                            x = u * u * p0.x + 2f * u * s * ctrl.x + s * s * p2.x,
                            y = u * u * p0.y + 2f * u * s * ctrl.y + s * s * p2.y,
                            z = u * u * p0.z + 2f * u * s * ctrl.z + s * s * p2.z,
                        )
                        val st = seatLookTarget()
                        camera.lookAt(Position(
                            x = TARGET.x + (st.x - TARGET.x) * s,
                            y = TARGET.y + (st.y - TARGET.y) * s,
                            z = TARGET.z + (st.z - TARGET.z) * s,
                        ))
                        camera.focalLength = EXTERIOR_FOCAL + (INTERIOR_FOCAL - EXTERIOR_FOCAL) * s.toDouble()
                        camera.near = EXTERIOR_NEAR + (INTERIOR_NEAR - EXTERIOR_NEAR) * s
                        env.indirectLight?.intensity = 13_500f + 14_500f * s

                        if (mix[0] >= 1f && interior) {
                            applySeatCamera()
                            transitioning = false
                        } else if (mix[0] <= 0f && !interior) {
                            parts.setInteriorDetailsVisible(false)
                            camera.focalLength = EXTERIOR_FOCAL
                            camera.near = EXTERIOR_NEAR
                            camera.position = CAMERA
                            camera.lookAt(TARGET)
                            transitioning = false
                            settledExterior = true
                        }
                    }

                    if (parts.animate(open, headlights, fogLights, reverse, driving, speedKmh, dt)) {
                        changed = true
                    }
                    // Pleine définition dès que tout est immobile : les 2 images de « stabilisation »
                    // (et toutes les suivantes, puisque le rendu s'arrête) sont nettes et lissées.
                    val wantHi = !changed
                    if (wantHi != hiRes[0]) {
                        hiRes[0] = wantHi
                        view.dynamicResolutionOptions = if (wantHi) hiResOptions() else lowResOptions()
                    }
                    changed
                }
            },
        )
    }
}

/** Pièces animées du modèle : ouvrants, phares, roues et voie routière. */
private class CarParts {
    var carNode: ModelNode? = null
    var baseCarPos: Position = Position()
    var groundY: Float = -0.5f
    var hinges: Map<Opening, Node> = emptyMap()
    var leftWheels: List<Node> = emptyList()
    var rightWheels: List<Node> = emptyList()
    var interiorDetailMeshes: List<Node> = emptyList()
    var turntableNodes: List<Node> = emptyList()
    var roadRenderables: List<Node> = emptyList()
    var dashPairs: List<Pair<CubeNode, CubeNode>> = emptyList()

    var headlightMaterials: List<MaterialInstance> = emptyList()
    var headlightReflectorMaterials: List<MaterialInstance> = emptyList()
    var headlightGlassMaterials: List<MaterialInstance> = emptyList()
    var dashBeamMaterials: List<MaterialInstance> = emptyList()
    var taillightMaterials: List<MaterialInstance> = emptyList()
    var reverseMaterials: List<MaterialInstance> = emptyList()
    var fogMaterials: List<MaterialInstance> = emptyList()
    var headlightSpots: List<LightNode> = emptyList()
    var fogSpots: List<LightNode> = emptyList()

    var lastOpen: Set<Opening> = emptySet()
    var lastHeadlights = false
    var lastFog = false
    var lastReverse = false
    var wasDriving = false

    private var interiorDetailsShown: Boolean? = null
    private var roadShown: Boolean? = null
    private var headAttached = false
    private var fogAttached = false
    private val openness = mutableMapOf<Opening, Float>()
    private var head = -1f
    private var fog = -1f
    private var rev = -1f
    private var wheelDeg = 0f
    private var roadScroll = 0f
    private var suspPhase = 0f

    fun setInteriorDetailsVisible(show: Boolean) {
        if (interiorDetailsShown == show) return
        interiorDetailsShown = show
        interiorDetailMeshes.forEach { it.isVisible = show }
    }

    private fun setRoadVisible(show: Boolean): Boolean {
        if (roadShown == show) return false
        roadShown = show
        turntableNodes.forEach { it.isVisible = !show }
        roadRenderables.forEach { it.isVisible = show }
        if (!show) {
            carNode?.position = baseCarPos
        }
        return true
    }

    private fun setGlow(mats: List<MaterialInstance>, r: Float, g: Float, b: Float, strength: Float) {
        mats.forEach {
            runCatching {
                it.setParameter("emissiveFactor", r, g, b)
                if (it.material.hasParameter("emissiveStrength")) it.setParameter("emissiveStrength", strength)
            }
        }
    }

    /** Anime les ouvrants, les feux et le roulage ; renvoie true si au moins un élément a bougé. */
    fun animate(
        open: Set<Opening>,
        headlightsOn: Boolean,
        fogOn: Boolean,
        reverseOn: Boolean,
        driving: Boolean,
        speedKmh: Float,
        dt: Float,
    ): Boolean {
        var active = false
        val root = carNode

        // 1. Animation de roulage : rotation des 4 jantes + défilement de la route + micro-suspension.
        if (setRoadVisible(driving)) active = true
        if (driving) {
            active = true
            // Courbe anti-stroboscope (évite l'effet roue qui recule sur les 5 branches à 28 FPS).
            val degPerSec = if (speedKmh <= 50f) speedKmh * 10.2f else 510f + (speedKmh - 50f).coerceAtMost(90f) * 1.8f
            wheelDeg = (wheelDeg + degPerSec * dt) % 360f
            leftWheels.forEach { it.rotation = Rotation(x = wheelDeg) }
            rightWheels.forEach { it.rotation = Rotation(x = -wheelDeg) }

            val roadSpeed = (speedKmh / 3.6f).coerceIn(2.2f, 26f) * 0.48f
            roadScroll = (roadScroll + roadSpeed * dt) % DASH_PERIOD
            val yDash = groundY - 0.005f
            for (i in dashPairs.indices) {
                val z = DASH_Z_MAX - (i * DASH_PERIOD + roadScroll)
                // Atténuation douce en entrée (devant le capot) et en sortie (derrière le coffre).
                val fadeFront = ((DASH_Z_MAX - z) / 0.75f).coerceIn(0.12f, 1f)
                val fadeRear = ((z - DASH_Z_MIN) / 0.75f).coerceIn(0.12f, 1f)
                val zScale = minOf(fadeFront, fadeRear)
                val (dl, dr) = dashPairs[i]
                dl.position = Position(x = 0.96f, y = yDash, z = z)
                dr.position = Position(x = -0.96f, y = yDash, z = z)
                dl.scale = Scale(1f, 1f, zScale)
                dr.scale = Scale(1f, 1f, zScale)
            }

            suspPhase = (suspPhase + dt * (2.5f + speedKmh * 0.04f)) % 62.8318f
            val bounce = sin(suspPhase * 3.1f) * 0.0024f + sin(suspPhase * 7.3f) * 0.0010f
            root?.position = Position(baseCarPos.x, baseCarPos.y + bounce, baseCarPos.z)
        }

        // 2. Charnières des ouvrants.
        val k = 1f - exp(-6.5f * dt)
        hinges.forEach { (opening, node) ->
            val target = if (opening in open) 1f else 0f
            val current = openness[opening]
            if (current != null && abs(target - current) < 1e-3f) {
                if (current != target) {
                    openness[opening] = target
                    val hinge = HINGES.getValue(opening)
                    node.rotation = if (hinge.alongX) Rotation(x = hinge.angle * target) else Rotation(z = hinge.angle * target)
                    active = true
                }
                return@forEach
            }
            val next = if (current == null) target else current + (target - current) * k
            openness[opening] = next
            val hinge = HINGES.getValue(opening)
            node.rotation = if (hinge.alongX) Rotation(x = hinge.angle * next) else Rotation(z = hinge.angle * next)
            active = true
        }

        // 3. Feux avant, feux arrière, antibrouillards et recul (spots détachés à 0 pour soulager le shader).
        val lk = 1f - exp(-10f * dt)

        val targetHead = if (headlightsOn) 1f else 0f
        if (head < 0f || abs(targetHead - head) > 1e-3f) {
            head = if (head < 0f) targetHead else {
                val n = head + (targetHead - head) * lk
                if (abs(targetHead - n) < 5e-3f) targetHead else n
            }
            setGlow(headlightMaterials, head, 0.97f * head, 0.90f * head, HEADLIGHT_GLOW)
            setGlow(headlightReflectorMaterials, head, 0.95f * head, 0.86f * head, HEADLIGHT_REFL_GLOW)
            setGlow(headlightGlassMaterials, head, 0.95f * head, 0.86f * head, HEADLIGHT_GLASS_GLOW)
            setGlow(dashBeamMaterials, 0.08f * head, 1.0f * head, 0.25f * head, 4.5f)
            setGlow(taillightMaterials, head, 0.03f * head, 0.02f * head, TAILLIGHT_GLOW)
            val needAttach = head > 0.01f
            if (needAttach && !headAttached && root != null) {
                headlightSpots.forEach { root.addChildNode(it) }
                headAttached = true
            } else if (!needAttach && headAttached && root != null) {
                headlightSpots.forEach { root.removeChildNode(it) }
                headAttached = false
            }
            if (headAttached) headlightSpots.forEach { it.intensity = HEADLIGHT_LUMENS * head }
            active = true
        }

        val targetFog = if (fogOn) 1f else 0f
        if (fog < 0f || abs(targetFog - fog) > 1e-3f) {
            fog = if (fog < 0f) targetFog else {
                val n = fog + (targetFog - fog) * lk
                if (abs(targetFog - n) < 5e-3f) targetFog else n
            }
            setGlow(fogMaterials, fog, 0.94f * fog, 0.78f * fog, FOG_GLOW)
            val needAttach = fog > 0.01f
            if (needAttach && !fogAttached && root != null) {
                fogSpots.forEach { root.addChildNode(it) }
                fogAttached = true
            } else if (!needAttach && fogAttached && root != null) {
                fogSpots.forEach { root.removeChildNode(it) }
                fogAttached = false
            }
            if (fogAttached) fogSpots.forEach { it.intensity = FOG_LUMENS * fog }
            active = true
        }

        val targetRev = if (reverseOn) 1f else 0f
        if (rev < 0f || abs(targetRev - rev) > 1e-3f) {
            rev = if (rev < 0f) targetRev else {
                val n = rev + (targetRev - rev) * lk
                if (abs(targetRev - n) < 5e-3f) targetRev else n
            }
            setGlow(reverseMaterials, rev, 0.98f * rev, 0.92f * rev, REVERSE_GLOW)
            active = true
        }

        return active
    }
}

private class Hinge(val node: String, val alongX: Boolean, val angle: Float)

/** 75 % de la définition, échelle fixe (pas de « grouillement ») : pour rester fluide pendant les animations. */
private fun lowResOptions() = View.DynamicResolutionOptions().apply {
    enabled = true
    minScale = 0.75f
    maxScale = 0.75f
    homogeneousScaling = true
    quality = View.QualityLevel.MEDIUM
}

/** Pleine définition de l'écran : utilisée dès que la voiture est immobile (rendu à 0 FPS). */
private fun hiResOptions() = View.DynamicResolutionOptions().apply {
    enabled = false
}

private val HINGES = mapOf(
    Opening.FRONT_LEFT to Hinge("charniere_porte_AVG", false, -62f),
    Opening.FRONT_RIGHT to Hinge("charniere_porte_AVD", false, 62f),
    Opening.REAR_LEFT to Hinge("charniere_porte_ARG", false, -62f),
    Opening.REAR_RIGHT to Hinge("charniere_porte_ARD", false, 62f),
    Opening.TRUNK to Hinge("charniere_coffre", true, 78f),
    Opening.HOOD to Hinge("charniere_capot", true, -48f),
)

private val FOCUS = mapOf(
    Opening.FRONT_LEFT to -50f, Opening.REAR_LEFT to -130f,
    Opening.FRONT_RIGHT to 50f, Opening.REAR_RIGHT to 130f,
    Opening.TRUNK to 160f, Opening.HOOD to 20f,
)

private val INTERIOR_DETAIL_NODES = setOf(
    "p308_dash_premium",
    "p308_decals_gauges",
    "p308_needle_tacho",
    "p308_needle_speedo",
    "p308_needle_oil",
    "p308_needle_fuel",
)

private val HEAD_LAMPS = listOf(Position(0.66f, 0.72f, 1.92f), Position(-0.66f, 0.72f, 1.92f))
private val FOG_LAMPS = listOf(Position(0.70f, 0.43f, 1.88f), Position(-0.70f, 0.43f, 1.88f))
private const val HERO_ANGLE = -28f
private const val DRIVE_THRESHOLD_KMH = 2.0f
private const val DASH_COUNT = 5
private const val DASH_PERIOD = 1.36f
private const val DASH_Z_MAX = 3.20f
private const val DASH_Z_MIN = -3.60f

private const val HEADLIGHT_GLOW = 18f
private const val HEADLIGHT_REFL_GLOW = 8.5f
private const val HEADLIGHT_GLASS_GLOW = 5.5f
private const val HEADLIGHT_LUMENS = 220_000f
private const val TAILLIGHT_GLOW = 6.5f
private const val REVERSE_GLOW = 7f
private const val FOG_GLOW = 7f
private const val FOG_LUMENS = 110_000f

// v0.28 : cadrage revu sur le vrai écran (1280 × 720) : à 30 et en visant -0,08, le bas de la voiture et la plaque
// arrière étaient coupés par le bas du cadre.
private const val EXTERIOR_FOCAL = 25.0
private const val INTERIOR_FOCAL = 15.5

private const val EXTERIOR_NEAR = 0.20f
private const val INTERIOR_NEAR = 0.03f
private const val CAMERA_FAR = 14f

private val CAMERA = Position(0f, 1.18f, 3.15f)
private val TARGET = Position(0f, -0.38f, 0f)

private val EYE = Position(0.325f, 1.08f, -0.14f)
private val LOOK_AHEAD = Position(0.325f, 0.86f, 1.40f)
