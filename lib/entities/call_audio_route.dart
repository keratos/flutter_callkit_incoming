/// Sortie audio d'un appel, telle que Telecom l'expose (Android).
///
/// Les noms des valeurs sont ceux échangés avec le code natif
/// (`CallkitAudioRoute.kt`) : ne pas les renommer.
enum CallAudioRoute {
  earpiece,
  speaker,
  bluetooth,
  wiredHeadset,
  streaming;

  /// Valeur correspondant à [name], null pour un nom inconnu ou absent.
  static CallAudioRoute? fromName(Object? name) =>
      name is String ? values.asNameMap()[name] : null;
}

/// Route audio courante d'un appel et routes que Telecom propose.
class CallAudioRouteState {
  const CallAudioRouteState({required this.route, required this.available});

  /// Lit le corps envoyé par le natif : `{route, available}`. Les noms
  /// inconnus sont ignorés plutôt que de faire échouer la lecture.
  factory CallAudioRouteState.fromMap(Map<Object?, Object?> map) {
    final available = map['available'];
    return CallAudioRouteState(
      route: CallAudioRoute.fromName(map['route']),
      available: available is List
          ? available
              .map(CallAudioRoute.fromName)
              .whereType<CallAudioRoute>()
              .toList(growable: false)
          : const [],
    );
  }

  /// Route active, null tant que Telecom ne l'a pas encore annoncée.
  final CallAudioRoute? route;

  final List<CallAudioRoute> available;

  @override
  String toString() =>
      'CallAudioRouteState(route: ${route?.name}, available: '
      '${available.map((r) => r.name).toList()})';
}
