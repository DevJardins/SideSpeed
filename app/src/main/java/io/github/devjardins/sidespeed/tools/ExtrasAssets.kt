package io.github.devjardins.sidespeed.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class DeviceMatch(val manufacturer: String, val model: String, val device: String)

data class ExtrasListMeta(
    val id: String,
    val variant: String,
    val file: String,
    val tv: String,
    val os: String,
    val brand: String,
    val match: DeviceMatch?,
)

data class ExtrasEntry(
    val pkg: String,
    val note: String,
    val home: Boolean,
    val setup: Boolean,
    val optional: Boolean,
)

data class ExtrasIndex(val upstreamCommit: String, val lists: List<ExtrasListMeta>)

fun loadExtrasIndex(context: Context): ExtrasIndex {
    val text = context.assets.open("extras/index.json").bufferedReader().use { it.readText() }
    return parseExtrasIndex(text)
}

fun loadExtrasList(context: Context, file: String): List<ExtrasEntry> {
    val text = context.assets.open("extras/$file").bufferedReader().use { it.readText() }
    return parseExtrasList(text)
}

internal fun parseExtrasIndex(text: String): ExtrasIndex {
    val root = JSONObject(text)
    val lists = root.getJSONArray("lists").let { arr ->
        List(arr.length()) { i -> parseListMeta(arr.getJSONObject(i)) }
    }
    return ExtrasIndex(upstreamCommit = root.getString("upstream_commit"), lists = lists)
}

internal fun parseExtrasList(text: String): List<ExtrasEntry> {
    val arr = JSONArray(text)
    return List(arr.length()) { i ->
        val o = arr.getJSONObject(i)
        ExtrasEntry(
            pkg = o.getString("package"),
            note = o.optString("note", ""),
            home = o.optBoolean("home", false),
            setup = o.optBoolean("setup", false),
            optional = o.optBoolean("optional", false),
        )
    }
}

private fun parseListMeta(o: JSONObject): ExtrasListMeta {
    val match = o.opt("match")?.takeIf { it != JSONObject.NULL }?.let { m ->
        val obj = m as JSONObject
        DeviceMatch(
            manufacturer = obj.getString("manufacturer"),
            model = obj.getString("model"),
            device = obj.getString("device"),
        )
    }
    return ExtrasListMeta(
        id = o.getString("id"),
        variant = o.getString("variant"),
        file = o.getString("file"),
        tv = o.getString("tv"),
        os = o.getString("os"),
        brand = o.getString("brand"),
        match = match,
    )
}

fun upstreamCommitShort(full: String): String = full.take(7)