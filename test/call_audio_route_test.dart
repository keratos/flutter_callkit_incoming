import 'package:flutter_callkit_incoming/entities/call_audio_route.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('CallAudioRoute.fromName', () {
    test('lit les noms envoyés par le natif', () {
      expect(CallAudioRoute.fromName('earpiece'), CallAudioRoute.earpiece);
      expect(CallAudioRoute.fromName('speaker'), CallAudioRoute.speaker);
      expect(CallAudioRoute.fromName('bluetooth'), CallAudioRoute.bluetooth);
      expect(
        CallAudioRoute.fromName('wiredHeadset'),
        CallAudioRoute.wiredHeadset,
      );
      expect(CallAudioRoute.fromName('streaming'), CallAudioRoute.streaming);
    });

    test('renvoie null pour un nom inconnu ou absent', () {
      expect(CallAudioRoute.fromName('carKit'), isNull);
      expect(CallAudioRoute.fromName(null), isNull);
      expect(CallAudioRoute.fromName(4), isNull);
    });
  });

  group('CallAudioRouteState.fromMap', () {
    test('lit la route et les routes disponibles', () {
      final state = CallAudioRouteState.fromMap({
        'id': 'abc',
        'route': 'bluetooth',
        'available': ['earpiece', 'speaker', 'bluetooth'],
      });
      expect(state.route, CallAudioRoute.bluetooth);
      expect(state.available, [
        CallAudioRoute.earpiece,
        CallAudioRoute.speaker,
        CallAudioRoute.bluetooth,
      ]);
    });

    test('route encore inconnue : null, sans faire échouer la lecture', () {
      final state = CallAudioRouteState.fromMap({
        'route': null,
        'available': ['earpiece', 'speaker'],
      });
      expect(state.route, isNull);
      expect(state.available, [CallAudioRoute.earpiece, CallAudioRoute.speaker]);
    });

    test('ignore les routes inconnues et un champ available invalide', () {
      expect(
        CallAudioRouteState.fromMap({
          'route': 'speaker',
          'available': ['speaker', 'carKit', null],
        }).available,
        [CallAudioRoute.speaker],
      );
      expect(CallAudioRouteState.fromMap({'available': 'speaker'}).available,
          isEmpty);
      expect(CallAudioRouteState.fromMap({}).available, isEmpty);
    });
  });
}
