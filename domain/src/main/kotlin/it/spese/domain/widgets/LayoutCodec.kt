package it.spese.domain.widgets

import kotlinx.serialization.json.*

/** Salvataggio delle composizioni come testo JSON (nelle preferenze del telefono e nel backup). */
object LayoutCodec {
    fun encode(map: Map<WidgetPage, List<WidgetInstance>>): String = buildJsonObject {
        map.forEach { (page, items) ->
            put(page.name, buildJsonArray {
                items.forEach { i -> add(buildJsonObject {
                    put("u", i.uid); put("w", i.widgetId); put("on", i.enabled)
                    if (i.options.isNotEmpty()) put("o", buildJsonObject { i.options.forEach { (k, v) -> put(k, v) } })
                }) }
            })
        }
    }.toString()

    fun decode(s: String?): Map<WidgetPage, List<WidgetInstance>> {
        if (s.isNullOrBlank()) return emptyMap()
        val root = runCatching { Json.parseToJsonElement(s) as? JsonObject }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<WidgetPage, List<WidgetInstance>>()
        for (page in WidgetPage.entries) {
            val arr = root[page.name] as? JsonArray ?: continue
            out[page] = arr.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
                val uid = str("u") ?: return@mapNotNull null
                val wid = str("w") ?: return@mapNotNull null
                val opts = (o["o"] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.let { c -> k to c } }?.toMap() ?: emptyMap()
                WidgetInstance(uid, wid, str("on") != "false", opts)
            }
        }
        return out
    }
}
