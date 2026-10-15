package com.yodrop.core.session

internal enum class SessionEvent {
    GRAB, CLAIM, DECISION, ATTACH, CANCEL, RESET,
    SCAN, OFFER, ACCEPT, DENY
}

internal object SessionPolicy {
    fun senderTerminal(state: SenderState): Boolean =
        state is SenderState.Done ||
        state is SenderState.Cancelled ||
        (state is SenderState.Failed && !state.canResume)

    fun receiverTerminal(state: ReceiverState): Boolean =
        state is ReceiverState.Done ||
        state is ReceiverState.Cancelled ||
        (state is ReceiverState.Failed && !state.canResume)

    fun sender(state: SenderState, event: SessionEvent): Boolean =
        when (event) {
            SessionEvent.GRAB -> state is SenderState.Idle
            SessionEvent.CLAIM -> state is SenderState.Advertising
            SessionEvent.DECISION -> state is SenderState.AwaitAccept
            SessionEvent.ATTACH ->
                state is SenderState.Linking ||
                state is SenderState.Auth ||
                state is SenderState.Transferring ||
                (state is SenderState.Failed && state.canResume)
            SessionEvent.CANCEL ->
                state !is SenderState.Idle && !senderTerminal(state)
            SessionEvent.RESET -> senderTerminal(state)
            else -> false
        }

    fun receiver(state: ReceiverState, event: SessionEvent): Boolean =
        when (event) {
            SessionEvent.SCAN -> state is ReceiverState.Idle
            SessionEvent.OFFER -> state is ReceiverState.Scanning
            SessionEvent.CLAIM -> state is ReceiverState.OfferSeen
            SessionEvent.ACCEPT, SessionEvent.DENY ->
                state is ReceiverState.AcceptPrompt
            SessionEvent.ATTACH -> state is ReceiverState.Linking ||
                (state is ReceiverState.Failed && state.canResume)
            SessionEvent.CANCEL ->
                state !is ReceiverState.Idle && !receiverTerminal(state)
            SessionEvent.RESET -> receiverTerminal(state)
            else -> false
        }
}
