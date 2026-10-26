package com.tvguardian.core

/**
 * Where things live in Firestore. Must match firebase/firestore.rules.
 *
 * pairingCodes/{code}                          created by an unpaired TV, claimed by the parent
 * families/{parentUid}                         one family per parent account
 * families/{parentUid}/devices/{tvUid}         one doc per paired TV (doc id = the TV's anonymous uid)
 *   .../state/now                              what the TV is showing right now (written by the TV)
 *   .../commands/{id}                          commands from the parent (status updated by the TV)
 *   .../history/{id}                           finished watch sessions (written by the TV)
 */
object FirestorePaths {
    const val PAIRING_CODES = "pairingCodes"
    const val STATE = "state"
    const val COMMANDS = "commands"
    const val HISTORY = "history"

    /** Everything stored under a device doc, deleted along with it when the parent removes a TV. */
    val DEVICE_SUBCOLLECTIONS = listOf(STATE, COMMANDS, HISTORY)

    fun family(familyId: String) = "families/$familyId"
    fun devices(familyId: String) = "families/$familyId/devices"
    fun device(familyId: String, deviceId: String) = "families/$familyId/devices/$deviceId"
    fun nowState(familyId: String, deviceId: String) = "${device(familyId, deviceId)}/$STATE/now"
    fun commands(familyId: String, deviceId: String) = "${device(familyId, deviceId)}/$COMMANDS"
    fun history(familyId: String, deviceId: String) = "${device(familyId, deviceId)}/$HISTORY"
}
