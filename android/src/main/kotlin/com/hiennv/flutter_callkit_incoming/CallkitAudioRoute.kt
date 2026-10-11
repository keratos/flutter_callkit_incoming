package com.hiennv.flutter_callkit_incoming

import android.telecom.CallAudioState

/**
 * Noms des routes audio échangés avec Dart, et leur correspondance avec les
 * constantes Telecom.
 *
 * Les types de [android.telecom.CallEndpoint] (API 34) sont recopiés en clair
 * plutôt que référencés : cet objet est chargé sur toutes les versions
 * d'Android, et la classe CallEndpoint n'existe pas avant la 14.
 */
object CallkitAudioRoute {
    const val EARPIECE = "earpiece"
    const val SPEAKER = "speaker"
    const val BLUETOOTH = "bluetooth"
    const val WIRED_HEADSET = "wiredHeadset"
    const val STREAMING = "streaming"

    /** `CallEndpoint.TYPE_*` → nom, null pour un type inconnu. */
    fun fromEndpointType(type: Int): String? = when (type) {
        1 -> EARPIECE
        2 -> BLUETOOTH
        3 -> WIRED_HEADSET
        4 -> SPEAKER
        5 -> STREAMING
        else -> null
    }

    private val audioStateRoutes = listOf(
        CallAudioState.ROUTE_EARPIECE to EARPIECE,
        CallAudioState.ROUTE_BLUETOOTH to BLUETOOTH,
        CallAudioState.ROUTE_WIRED_HEADSET to WIRED_HEADSET,
        CallAudioState.ROUTE_SPEAKER to SPEAKER,
        CallAudioState.ROUTE_STREAMING to STREAMING,
    )

    /** `CallAudioState.ROUTE_*` (une seule route) → nom. */
    fun fromAudioStateRoute(route: Int): String? =
        audioStateRoutes.firstOrNull { it.first == route }?.second

    /** Masque `supportedRouteMask` → noms des routes qu'il contient. */
    fun fromAudioStateMask(mask: Int): List<String> =
        audioStateRoutes.filter { mask and it.first != 0 }.map { it.second }

    /** Nom → `CallAudioState.ROUTE_*`. */
    fun toAudioStateRoute(name: String): Int? =
        audioStateRoutes.firstOrNull { it.second == name }?.first

    /**
     * Routes à essayer, dans l'ordre, pour honorer une demande.
     *
     * « earpiece » veut dire « ni haut-parleur ni Bluetooth » : casque filaire
     * branché, Android n'expose plus d'écouteur, le casque en tient lieu.
     */
    fun candidates(name: String): List<String> = when (name) {
        EARPIECE -> listOf(EARPIECE, WIRED_HEADSET)
        else -> listOf(name)
    }
}
