package com.unsilence.app.data.media

import com.unsilence.app.data.relay.NetworkConditions
import com.unsilence.app.data.relay.NetworkMonitor
import com.unsilence.app.data.relay.NetworkState
import com.unsilence.app.data.relay.RelayPreferencesStore
import com.unsilence.app.domain.model.AutomaticMediaPreferences
import com.unsilence.app.domain.model.AutomaticMediaMode
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class MediaLoadPermissions(val images: Boolean = true, val previews: Boolean = true)

internal val NetworkConditions.blocksAutomaticMedia: Boolean
    get() = isMetered && state != NetworkState.OFFLINE

internal fun automaticMediaAllowed(mode: AutomaticMediaMode, network: NetworkConditions): Boolean = when (mode) {
    AutomaticMediaMode.ALWAYS -> true
    AutomaticMediaMode.UNMETERED_ONLY -> !network.blocksAutomaticMedia
    AutomaticMediaMode.NEVER -> false
}

/** Shared UI/warm-lane decisions; contains no URLs, content, or per-row state. */
@Singleton
class MediaLoadingPolicy @Inject constructor(
    private val preferences: RelayPreferencesStore,
    private val network: NetworkMonitor,
) {
    val current: MediaLoadPermissions
        get() = mediaLoadPermissions(preferences.automaticMediaPreferences.value, network.currentConditions)

    val permissions = combine(preferences.automaticMediaPreferences, network.conditions, ::mediaLoadPermissions)
        .stateIn(CoroutineScope(SupervisorJob() + Dispatchers.Default), SharingStarted.Eagerly, current)
}

internal fun mediaLoadPermissions(preferences: AutomaticMediaPreferences?, conditions: NetworkConditions): MediaLoadPermissions =
    if (preferences == null) MediaLoadPermissions(images = false, previews = false)
    else MediaLoadPermissions(
        automaticMediaAllowed(preferences.images, conditions),
        automaticMediaAllowed(preferences.previews, conditions),
    )
