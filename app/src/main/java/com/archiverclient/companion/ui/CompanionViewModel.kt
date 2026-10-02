package com.archiverclient.companion.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.archiverclient.companion.data.AppDatabase
import com.archiverclient.companion.data.Peer
import com.archiverclient.companion.data.Settings
import com.archiverclient.companion.net.DeviceIdentity
import com.archiverclient.companion.net.Pairing
import com.archiverclient.companion.service.CompanionService
import com.archiverclient.companion.work.Scheduling
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PairingState(
    val busy: Boolean = false,
    val message: String? = null,
    val succeeded: Boolean = false,
)

class CompanionViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.of(app)
    private val settings = Settings(app)

    val peers: StateFlow<List<Peer>> =
        db.peers().watch().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val indexed: StateFlow<Int> =
        db.media().countPresent().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val hashed: StateFlow<Int> =
        db.media().countHashed().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _pairing = MutableStateFlow(PairingState())
    val pairing: StateFlow<PairingState> = _pairing.asStateFlow()

    val deviceName: String get() = DeviceIdentity.displayName()

    val serverPort: Int get() = settings.serverPort

    fun pair(raw: String) {
        if (_pairing.value.busy) return
        _pairing.value = PairingState(busy = true)
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()

            // The server has to be up before we pair, because the port we are about to
            // promise has to be a port something is actually listening on.
            CompanionService.ensureRunning(app)
            var port = settings.serverPort
            var waited = 0
            while (port <= 0 && waited < 5_000) {
                kotlinx.coroutines.delay(200)
                waited += 200
                port = settings.serverPort
            }
            if (port <= 0) {
                _pairing.value = PairingState(message = "This phone's server did not start.")
                return@launch
            }

            when (val outcome = Pairing(app).pair(raw, port)) {
                is Pairing.Outcome.Paired -> {
                    Scheduling.enableAll(app)
                    CompanionService.nudge(app, "just paired")
                    _pairing.value = PairingState(
                        message = "Paired with ${outcome.peer.displayName}.",
                        succeeded = true,
                    )
                }
                Pairing.Outcome.Refused -> _pairing.value = PairingState(
                    message = "That code had already been used or run out. " +
                        "Press \"Sign in again\" on the computer for a fresh one."
                )
                is Pairing.Outcome.Unreachable -> _pairing.value = PairingState(
                    message = "Could not reach that computer: ${outcome.reason}. " +
                        "Check both are on the same wifi."
                )
                Pairing.Outcome.NotAPairingCode -> _pairing.value = PairingState(
                    message = "That is not an Archiver pairing code."
                )
            }
        }
    }

    fun clearPairingMessage() {
        _pairing.value = PairingState()
    }

    fun indexNow() {
        val app = getApplication<Application>()
        Scheduling.enqueueIndex(app, full = true)
        Scheduling.hashNow(app)
    }

    fun unpair(peer: Peer) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            db.peers().forget(peer.token)
            if (db.peers().count() == 0) {
                Scheduling.disableAll(app)
                CompanionService.stop(app)
            }
        }
    }
}
