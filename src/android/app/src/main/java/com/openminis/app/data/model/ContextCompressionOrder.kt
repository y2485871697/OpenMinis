package com.openminis.app.data.model

// One priority list for both direct models and groups. Old configurations keep
// their displayed group-first order until the user rearranges it.
internal fun ProviderConfig.contextCompressionTargets(): List<String> {
    val entries = modelEntries.map { it.id }.toSet()
    val groups = modelGroups.map { it.id }.toSet()
    val selected = (contextCompressionGroupIds.filter { it in groups }.map { "group:$it" } +
        contextCompressionModelEntryIds.filter { it in entries }.map { "entry:$it" }).distinct()
    val selectedSet = selected.toSet()
    return (contextCompressionOrder.filter { it in selectedSet } + selected).distinct()
}

internal fun ProviderConfig.reorderedContextCompressionTargets(newOrder: List<String>): List<String>? {
    val current = contextCompressionTargets()
    return newOrder.toList().takeIf { it.size == current.size && it.toSet() == current.toSet() }
}

internal fun ProviderConfig.contextCompressionCandidates(): List<ModelEntry> {
    val entries = modelEntries.associateBy { it.id }
    val groups = modelGroups.associateBy { it.id }
    return contextCompressionTargets().flatMap { target ->
        if (target.startsWith("entry:")) listOf(target.removePrefix("entry:"))
        else groups[target.removePrefix("group:")]?.memberEntryIds.orEmpty()
    }.distinct().mapNotNull { entries[it] }
}
