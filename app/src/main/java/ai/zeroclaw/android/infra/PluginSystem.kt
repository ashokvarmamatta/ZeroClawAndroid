package ai.zeroclaw.android.infra

import android.content.Context
import ai.zeroclaw.android.service.ZeroClawService
import ai.zeroclaw.android.tools.Tool
import ai.zeroclaw.android.tools.ToolParam
import ai.zeroclaw.android.tools.ToolResult
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/**
 * PluginSystem — Phase 124: User-installable plugin packages (.zip skills).
 *
 * Plugins are .zip archives containing:
 * - manifest.json  — plugin metadata, tool definitions, hook registrations
 * - tools/[name].json — individual tool configs (name, description, params, endpoint)
 * - README.md      — optional documentation
 *
 * Plugin tools call a user-defined HTTP endpoint with the tool args and return
 * the response as the tool result. This allows custom skills without recompiling.
 *
 * Inspired by OpenClaw's plugin SDK and skill package system.
 */
class PluginSystem(private val context: Context) {

    data class PluginManifest(
        val id: String,
        val name: String,
        val version: String,
        val description: String,
        val author: String = "",
        val tools: List<PluginToolDef> = emptyList(),
        val hooks: List<PluginHookDef> = emptyList()
    )

    data class PluginToolDef(
        val name: String,
        val description: String,
        val endpoint: String,          // HTTP endpoint to call
        val method: String = "POST",
        val params: List<PluginParam> = emptyList()
    )

    data class PluginParam(
        val name: String,
        val type: String = "string",
        val description: String = "",
        val required: Boolean = false
    )

    data class PluginHookDef(
        val point: String,             // HookPoint enum name
        val endpoint: String           // HTTP endpoint to call with HookContext
    )

    data class InstalledPlugin(
        val manifest: PluginManifest,
        val installDir: File,
        val enabled: Boolean = true
    )

    companion object {
        const val PLUGINS_DIR = "plugins"

        @Volatile private var INSTANCE: PluginSystem? = null
        fun getInstance(context: Context): PluginSystem {
            return INSTANCE ?: synchronized(this) {
                PluginSystem(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val pluginsDir = File(context.filesDir, PLUGINS_DIR).also { it.mkdirs() }
    private val loadedPlugins = mutableMapOf<String, InstalledPlugin>()

    /**
     * Install a plugin from a .zip file path.
     */
    fun installFromZip(zipPath: String): Result<PluginManifest> = runCatching {
        val zipFile = File(zipPath)
        if (!zipFile.exists()) throw IllegalArgumentException("File not found: $zipPath")

        val manifestJson = extractManifest(zipFile)
            ?: throw IllegalArgumentException("No manifest.json found in zip")

        val manifest = parseManifest(manifestJson)
        val pluginDir = File(pluginsDir, manifest.id).also { it.mkdirs() }

        // Extract all files
        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(pluginDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { out -> zis.copyTo(out) }
                }
                entry = zis.nextEntry
            }
        }

        ZeroClawService.log("PLUGIN: installed '${manifest.name}' v${manifest.version}")
        loadPlugin(pluginDir, manifest)
        manifest
    }

    /**
     * Load all installed plugins from the plugins directory.
     */
    fun loadAll() {
        pluginsDir.listFiles()?.forEach { dir ->
            if (dir.isDirectory) {
                val manifestFile = File(dir, "manifest.json")
                if (manifestFile.exists()) {
                    try {
                        val manifest = parseManifest(manifestFile.readText())
                        loadPlugin(dir, manifest)
                    } catch (e: Exception) {
                        ZeroClawService.log("PLUGIN: failed to load '${dir.name}' — ${e.message}")
                    }
                }
            }
        }
        ZeroClawService.log("PLUGIN: loaded ${loadedPlugins.size} plugin(s)")
    }

    /**
     * Uninstall a plugin by ID.
     */
    fun uninstall(pluginId: String) {
        loadedPlugins.remove(pluginId)
        File(pluginsDir, pluginId).deleteRecursively()
        ZeroClawService.log("PLUGIN: uninstalled '$pluginId'")
    }

    /**
     * Get all registered tool definitions from all loaded plugins.
     */
    fun getPluginTools(): List<Tool> {
        return loadedPlugins.values
            .filter { it.enabled }
            .flatMap { plugin ->
                plugin.manifest.tools.map { toolDef ->
                    createPluginTool(toolDef, plugin)
                }
            }
    }

    /**
     * Get all loaded plugins.
     */
    fun getPlugins(): List<InstalledPlugin> = loadedPlugins.values.toList()

    /**
     * Enable/disable a plugin.
     */
    fun setEnabled(pluginId: String, enabled: Boolean) {
        val plugin = loadedPlugins[pluginId] ?: return
        loadedPlugins[pluginId] = plugin.copy(enabled = enabled)
    }

    private fun loadPlugin(dir: File, manifest: PluginManifest) {
        loadedPlugins[manifest.id] = InstalledPlugin(manifest, dir)

        // Register plugin hooks
        for (hookDef in manifest.hooks) {
            try {
                val hookPoint = HooksSystem.HookPoint.valueOf(hookDef.point)
                val endpoint = hookDef.endpoint
                HooksSystem.register("plugin_${manifest.id}_${hookDef.point}", hookPoint) { ctx ->
                    // Call the plugin's hook endpoint with the context
                    callPluginEndpoint(endpoint, ctx.payload, ctx.metadata) != null
                }
            } catch (_: Exception) {
                ZeroClawService.log("PLUGIN: invalid hook point '${hookDef.point}' in ${manifest.id}")
            }
        }
    }

    private fun createPluginTool(toolDef: PluginToolDef, plugin: InstalledPlugin): Tool {
        val params = toolDef.params.map { p ->
            ToolParam(p.name, p.type, p.description, p.required)
        }
        return object : Tool {
            override val name = "plugin_${toolDef.name}"
            override val description = "[Plugin: ${plugin.manifest.name}] ${toolDef.description}"
            override val parameters = params

            override suspend fun execute(args: Map<String, String>): ToolResult {
                if (!plugin.enabled) return ToolResult(false, "", "Plugin '${plugin.manifest.name}' is disabled")
                val result = callPluginEndpoint(toolDef.endpoint, JSONObject(args).toString(), emptyMap())
                return if (result != null) {
                    ToolResult(true, result)
                } else {
                    ToolResult(false, "", "Plugin endpoint call failed")
                }
            }
        }
    }

    private fun callPluginEndpoint(endpoint: String, payload: String, metadata: Map<String, Any>): String? {
        return try {
            val client = okhttp3.OkHttpClient()
            val body = JSONObject().apply {
                put("payload", payload)
                put("metadata", JSONObject(metadata.mapValues { it.value.toString() }))
            }.toString()
            val request = okhttp3.Request.Builder()
                .url(endpoint)
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        } catch (e: Exception) {
            ZeroClawService.log("PLUGIN: endpoint call failed — ${e.message}")
            null
        }
    }

    private fun extractManifest(zipFile: File): String? {
        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name == "manifest.json") {
                    return zis.readBytes().toString(Charsets.UTF_8)
                }
                entry = zis.nextEntry
            }
        }
        return null
    }

    private fun parseManifest(json: String): PluginManifest {
        val obj = JSONObject(json)
        val toolsArr = obj.optJSONArray("tools") ?: JSONArray()
        val hooksArr = obj.optJSONArray("hooks") ?: JSONArray()

        val tools = (0 until toolsArr.length()).map { i ->
            val t = toolsArr.getJSONObject(i)
            val paramsArr = t.optJSONArray("params") ?: JSONArray()
            val params = (0 until paramsArr.length()).map { j ->
                val p = paramsArr.getJSONObject(j)
                PluginParam(
                    name = p.optString("name"),
                    type = p.optString("type", "string"),
                    description = p.optString("description", ""),
                    required = p.optBoolean("required", false)
                )
            }
            PluginToolDef(
                name = t.optString("name"),
                description = t.optString("description"),
                endpoint = t.optString("endpoint"),
                method = t.optString("method", "POST"),
                params = params
            )
        }

        val hooks = (0 until hooksArr.length()).map { i ->
            val h = hooksArr.getJSONObject(i)
            PluginHookDef(point = h.optString("point"), endpoint = h.optString("endpoint"))
        }

        return PluginManifest(
            id = obj.optString("id"),
            name = obj.optString("name"),
            version = obj.optString("version", "1.0.0"),
            description = obj.optString("description"),
            author = obj.optString("author", ""),
            tools = tools,
            hooks = hooks
        )
    }
}
