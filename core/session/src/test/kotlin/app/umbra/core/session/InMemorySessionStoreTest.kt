package app.umbra.core.session

class InMemorySessionStoreTest : SessionStoreContract() {
    override fun newStore(): SessionStore = InMemorySessionStore(clock)
}
