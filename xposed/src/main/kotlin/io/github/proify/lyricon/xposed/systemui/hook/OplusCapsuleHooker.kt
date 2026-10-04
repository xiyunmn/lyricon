/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.xposed.systemui.hook

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.proify.lyricon.xposed.logger.YLog
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method

/** Seedling final presentation state, scoped to the status bar that owns the lyric. */
object OplusCapsuleHooker {
    private const val TAG = "OplusCapsule"
    private const val SEEDLING = "com.oplus.systemui.plugins.seedling"
    private const val CONTAINER = "$SEEDLING.capsule.ui.view.CapsuleContainer"
    private const val VERIFIED_BUILD = "PJZ110_17.0.0.101(SP02CN01)"
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val bindings = mutableListOf<Binding>()
    private val handles = mutableListOf<XposedInterface.HookHandle>()
    private val adapters = mutableMapOf<Class<*>, Adapter?>()
    private val commitDepth = ThreadLocal.withInitial { 0 }
    private var module: XposedModule? = null
    private var enabled = false

    private data class Adapter(val state: Field, val controller: Method, val count: Method)
    private class Binding(root: ViewGroup, initial: List<CapsuleObservation>) {
        val root = WeakReference(root)
        val session = CapsuleStateSession(initial)
        var observations = initial
        var revision = 0L
        var pendingHide: Runnable? = null
        var reason = "bind"
        var observedAt = SystemClock.uptimeMillis()
    }

    fun initialize(module: XposedModule, classLoader: ClassLoader) {
        if (this.module != null) return
        this.module = module
        enabled = runCatching {
            classLoader.loadClass("com.android.systemui.plugins.statusbar.CapsulePlugin")
        }.isSuccess || runCatching {
            classLoader.loadClass("com.oplus.systemui.statusbar.seeding.SeedlingPluginManager")
        }.isSuccess
        if (!enabled) return

        // The manager receives the actual plugin loader before plugin views are created.
        runCatching {
            val manager = classLoader.loadClass("com.oplus.systemui.statusbar.seeding.SeedlingPluginManager")
            val connected = manager.declaredMethods.single {
                it.name == "onPluginConnectedImpl" && it.parameterCount == 2
            }
            handles += module.hook(connected).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    runCatching { installAdapter(chain.args[0]?.javaClass?.classLoader) }
                        .onFailure { YLog.error(TAG, "Plugin adapter installation failed", it) }
                    return chain.proceed()
                }
            })
        }.onFailure { YLog.info(TAG, "Seedling manager unavailable; use attached views/legacy path") }

        // Older ROMs have no ContentState. Inspect relevant views and their own tree only.
        runCatching {
            val method = View::class.java.getDeclaredMethod("setVisibility", Int::class.javaPrimitiveType)
            handles += module.hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    val view = chain.thisObject as? View ?: return result
                    val name = view.javaClass.simpleName
                    if (commitDepth.get() == 0 && (name == "CapsuleContainer" || name == "CapsuleView")) {
                        refreshFor(view, "visibility")
                    }
                    return result
                }
            })
        }.onFailure { YLog.error(TAG, "Legacy visibility hook unavailable", it) }
    }

    @Synchronized
    private fun installAdapter(loader: ClassLoader?) {
        val module = module ?: return
        if (loader == null) return
        val type = runCatching { loader.loadClass(CONTAINER) }.getOrNull() ?: return
        if (adapters.containsKey(type)) return
        adapters[type] = null
        // These obfuscated method/controller names are verified for this ROM only.
        if (Build.DISPLAY != VERIFIED_BUILD) return
        runCatching {
            val stateType = loader.loadClass("$SEEDLING.capsule.data.model.ContentState")
            require(requireNotNull(stateType.enumConstants).map { (it as Enum<*>).name }.toSet() ==
                setOf("NORMAL", "MIN", "HIDE"))
            val field = type.declaredFields.single { it.type == stateType }.apply { isAccessible = true }
            val method = type.getDeclaredMethod(
                "h", loader.loadClass("$SEEDLING.capsule.ui.model.f"), Boolean::class.javaPrimitiveType
            )
            val adapter = Adapter(field, type.getDeclaredMethod("getController"),
                type.getDeclaredMethod("getCapsuleViewNum"))
            handles += module.hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    commitDepth.set((commitDepth.get() ?: 0) + 1)
                    val result = try { chain.proceed() } finally {
                        commitDepth.set((commitDepth.get() ?: 1) - 1)
                    }
                    (chain.thisObject as? View)?.let { refreshFor(it, "ContentState.commit") }
                    return result
                }
            })
            adapters[type] = adapter
            YLog.info(TAG, "Installed final-state hook: $method loader=$loader field=${field.name}")
        }.onFailure { YLog.warning(TAG, "Unrecognized Seedling signature; legacy path: ${it.message}") }
    }

    /** Main thread, before the first attached lyric layout. Replays immediately. */
    fun bind(root: ViewGroup, listener: (CapsuleDisplayState) -> Unit) {
        unbind(root)
        val binding = Binding(root, sample(root))
        bindings.add(binding)
        binding.session.subscribe { state ->
            YLog.info(TAG, "owner=${System.identityHashCode(root)} source=${binding.reason} " +
                "state=$state observedAt=${binding.observedAt} commitAt=${SystemClock.uptimeMillis()} " +
                "containers=${binding.observations}")
            listener(state)
        }
    }

    fun unbind(root: ViewGroup) {
        val old = bindings.filter { it.root.get() == null || it.root.get() === root }
        bindings.removeAll(old.toSet())
        old.forEach {
            it.pendingHide?.let(handler::removeCallbacks)
            it.session.close()
        }
    }

    fun isExpanded(root: ViewGroup): Boolean =
        CapsuleStateSession.merge(sample(root)) == CapsuleDisplayState.EXPANDED

    /** Catches ancestor visibility, reparenting and views attached before hook installation. */
    fun refresh(root: ViewGroup, reason: String = "layout") {
        bindings.firstOrNull { it.root.get() === root }?.let { refresh(it, reason) }
    }

    private fun refreshFor(view: View, reason: String) {
        val weakView = WeakReference(view)
        val observedAt = SystemClock.uptimeMillis()
        val action = Runnable {
            runCatching {
                val current = weakView.get() ?: return@runCatching
                bindings.toList().forEach { binding ->
                    val root = binding.root.get()
                    if (root != null && belongsTo(current, root)) {
                        refresh(binding, reason, observedAt = observedAt)
                    }
                }
            }.onFailure { YLog.error(TAG, "Capsule snapshot failed", it) }
        }
        // Read current views on main; never enqueue an already obsolete background snapshot.
        if (Looper.myLooper() == Looper.getMainLooper()) action.run() else handler.post(action)
    }

    private fun refresh(binding: Binding, reason: String, allowDelay: Boolean = true,
                        observedAt: Long = SystemClock.uptimeMillis()) {
        val root = binding.root.get() ?: return
        val observations = sample(root)
        val next = CapsuleStateSession.merge(observations)
        val legacySwitch = allowDelay && next == CapsuleDisplayState.ABSENT &&
            binding.session.state == CapsuleDisplayState.EXPANDED &&
            observations.any { it.contentState == null && it.attached && it.shown && it.home }
        if (legacySwitch) {
            if (binding.pendingHide == null) {
                val task = Runnable {
                    binding.pendingHide = null
                    refresh(binding, "legacy.settled", allowDelay = false)
                }
                binding.pendingHide = task
                handler.postDelayed(task, 80)
            }
            return
        }
        binding.pendingHide?.let(handler::removeCallbacks)
        binding.pendingHide = null
        binding.reason = reason
        binding.observedAt = observedAt
        binding.observations = observations
        binding.session.update(++binding.revision, observations)
    }

    private fun sample(root: ViewGroup): List<CapsuleObservation> {
        if (!enabled || !root.isAttachedToWindow) return emptyList()
        val result = mutableListOf<CapsuleObservation>()
        fun visit(view: View) {
            if (view.javaClass.simpleName == "CapsuleContainer") {
                if (!synchronized(this) { adapters.containsKey(view.javaClass) }) {
                    installAdapter(view.javaClass.classLoader)
                    synchronized(this) { adapters.putIfAbsent(view.javaClass, null) }
                }
                val adapter = synchronized(this) { adapters[view.javaClass] }
                val content = runCatching { (adapter?.state?.get(view) as? Enum<*>)?.name }.getOrNull()
                val controller = runCatching { adapter?.controller?.invoke(view)?.javaClass?.name }.getOrNull()
                val hasContent = runCatching { (adapter?.count?.invoke(view) as? Int ?: 0) > 0 }
                    .getOrDefault(false)
                result += CapsuleObservation(
                    contentState = content,
                    hasVisibleCapsule = hasVisibleCapsule(view),
                    attached = view.isAttachedToWindow,
                    shown = view.isShown,
                    home = if (adapter != null) controller == "$SEEDLING.capsule.n"
                        else !hasKeyguardAncestor(view, root),
                    hasContent = hasContent,
                    identity = System.identityHashCode(view)
                )
                return
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
        }
        visit(root)
        return result
    }

    private fun hasVisibleCapsule(view: View): Boolean {
        if (view.visibility != View.VISIBLE) return false
        if (view.javaClass.simpleName == "CapsuleView") return view.isAttachedToWindow
        if (view is ViewGroup) for (i in 0 until view.childCount) {
            if (hasVisibleCapsule(view.getChildAt(i))) return true
        }
        return false
    }

    private fun belongsTo(view: View, root: ViewGroup): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === root) return true
            current = current.parent as? View
        }
        return false
    }

    private fun hasKeyguardAncestor(view: View, root: ViewGroup): Boolean {
        var current: View? = view
        while (current != null && current !== root) {
            if (current.javaClass.simpleName.contains("Keyguard", ignoreCase = true)) return true
            current = current.parent as? View
        }
        return false
    }
}
