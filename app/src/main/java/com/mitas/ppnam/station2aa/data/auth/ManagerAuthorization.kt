package com.mitas.ppnam.station2aa.data.auth

import com.mitas.ppnam.station2aa.data.mqtt.dto.ManagerAction
import com.mitas.ppnam.station2aa.data.mqtt.dto.ScramPurpose
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Obtains the single-use `authorizationToken` a contract v4.1 privileged action requires.
 *
 * ### What changed from 4.0
 *
 * A manager used to type their password into the handheld and that password travelled inline on
 * the workflow message itself (`managerUsername` + `managerPassword` on force-close, collection
 * cancel, short-bag approval and inline override). Schema 4.1 rejects any message carrying a
 * `managerPassword` property outright. Now the manager's credentials are proved via SCRAM in a
 * separate exchange scoped to *one* action on *one* target, and only the resulting token travels.
 *
 * The token is deliberately not cached anywhere. It is single-use, expires in 60 seconds, and is
 * bound to this device, this `actionTarget` and this `managerAction` — so holding one would buy
 * nothing except a window in which a stolen handheld could replay a manager's approval. Each
 * privileged action re-authorizes.
 *
 * The approver is authorized on *their own* account, not the sender's session: a Manager operating
 * the handheld still has to be approved, which is why this takes an explicit username/password
 * rather than reading the current session.
 */
@Singleton
class ManagerAuthorization @Inject constructor(
    private val scramExchange: ScramExchange,
) {

    /**
     * Proves [managerUsername]/[managerPassword] for [action] on [targetId] and returns the token.
     *
     * @param targetId the bare entity id — the `Cycle:`/`IngredientCollection:`/`ShortBag:`/
     *   `IngredientScan:` prefix is applied here so no caller can scope a token wrongly.
     */
    suspend fun authorize(
        managerUsername: String,
        managerPassword: String,
        action: ManagerAction,
        targetId: String,
    ): Result<String> {
        if (managerUsername.isBlank() || managerPassword.isBlank()) {
            return Result.failure(Exception("Manager username and password are required"))
        }
        if (targetId.isBlank()) {
            return Result.failure(Exception("Cannot authorize ${action.wireValue} without a target"))
        }

        val proof = scramExchange.authenticate(
            username = managerUsername,
            password = managerPassword,
            purpose = ScramPurpose.MANAGER_ACTION,
            actionTarget = action.targetFor(targetId),
            managerAction = action.wireValue,
        ).getOrElse { return Result.failure(it) }

        val token = proof.authorizationToken
        if (token.isNullOrBlank()) {
            // A verified proof that yields no token means the manager authenticated but is not
            // permitted this action. Saying "approval was refused" is honest about which of the
            // two it was; "login failed" would send them back to retype a correct password.
            return Result.failure(
                Exception("Station 2 did not authorize this action for $managerUsername")
            )
        }
        return Result.success(token)
    }
}
