# 0016. The Credential Vault

Status: accepted. Architecture spec §12. Builds on the secret store (functional spec §28).

## Context

The spec's credential architecture is a federation of brokers (on the phone, trusted PCs, cloud security nodes), scoped
capabilities instead of raw secrets, brokered git operations, key custody tiers and risk-adaptive recovery. Today
Umbra holds one kind of credential, model provider keys, in an AES-GCM store whose key lives in the Android Keystore;
no node is paired and no git or signing credential is held. omp needs its provider key in its environment to call the
model, so a key that reaches a session can be read by what runs in that session.

## Decision

1. **The vault is an account, never a copy.** `CredentialVault` (core/control) records each credential's name, owner
   (provider), scope, creation, rotation and exposures, and follows the secret store; values stay in the store.
2. **Scopes decide which sessions get a key:** any session (the default, so a session can switch models), its
   provider's sessions only, or no agent. The resolver applies them; a custom provider whose key a session may not
   have is left out of that session's omp configuration, and a session whose own provider key is held back is refused
   with that reason.
3. **Every hand-over is an exposure grant in a hash-chained journal**: the agent's omp at session start, omp's
   terminal UI, the diagnostics test call (now given only the chosen provider's key) and the model list (which no
   longer receives keys held back from agents). The screen verifies the chain. What was handed out cannot be shown as
   not copied; it is never hidden.
4. **Custody is reported, not claimed**: the protecting key's algorithm and size, and the security level the keystore
   reports (software on the emulator, TEE or StrongBox on phones). No attestation chain is verified, and the screen
   says so. The key cannot be exported, so there is no backup tier: recovery is "none", stated plainly.
5. **Rotate, revoke and node trust are separate acts.** Rotating overwrites Umbra's copy; revoking deletes it, and the
   screen says the credential stays valid at its issuer until revoked there. No node trust exists to revoke.
6. The cloud/signing broker is shown as not set up, with what that means; nothing pretends to broker it. Git
   credentials are brokered since ADR 0022: bound to one URL, used only by root, given to no agent.

## Consequences

* A model key still reaches omp's environment; scopes reduce which sessions get it and the record shows every time.
* Verified on the emulator: the keystore's real security level, an exposure recorded per session start, "not for
  agents" refusing a session with a clear reason and recording nothing, "its provider only" handing the key to a
  session of that provider, the exposure chain intact.
