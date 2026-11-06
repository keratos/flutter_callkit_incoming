package com.hiennv.flutter_callkit_incoming

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Marques « appel mort » ([CallkitConnection.markDead]) et identifiants réutilisés.
 *
 * L'appli identifie un appel sortant par sa ligne (« line-1 ») : deux appels
 * successifs portent donc le même identifiant. À la fin du premier, Telecom a déjà
 * détruit la Connection quand ACTION_CALL_ENDED arrive : le receiver ne la trouve
 * plus et la marque morte. Sans [CallkitConnection.forgetDead] au départ du
 * suivant, cette marque périmée faisait refuser sa Connection (« Call already
 * ended ») : appel sans aucune existence système, alors que le SIP passait.
 */
class CallkitConnectionDeadCallsTest {

    @Before
    fun repartirDeZero() {
        CallkitConnection.clearAll()
    }

    @Test
    fun unNouvelAppelSurLaMemeLigneNHeritePasDeLaMarqueDuPrecedent() {
        CallkitConnection.markDead("line-1") // fin de l'appel précédent
        CallkitConnection.forgetDead("line-1") // startCall de l'appel suivant
        assertFalse(CallkitConnection.consumeDead("line-1"))
    }

    @Test
    fun uneFinRecueApresLeDepartDeLAppelLeFaitToujoursRefuser() {
        // La course que la garde de onCreateOutgoingConnection couvre : l'appel
        // retombe avant que Telecom ait créé sa Connection.
        CallkitConnection.forgetDead("line-1")
        CallkitConnection.markDead("line-1")
        assertTrue(CallkitConnection.consumeDead("line-1"))
    }

    @Test
    fun seuleLaLigneQuiRedemarrePerdSaMarque() {
        CallkitConnection.markDead("line-1")
        CallkitConnection.markDead("line-2")
        CallkitConnection.forgetDead("line-1")
        assertTrue(CallkitConnection.consumeDead("line-2"))
    }
}
