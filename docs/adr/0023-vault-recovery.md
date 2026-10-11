# 0023. Vault recovery: a code-sealed backup that restores secrets, not authority

Status: accepted. Architecture spec §12.3, AC-018. Extends ADR 0016 (the vault) and ADR 0022 (git credentials).

## Context

The vault's protecting key lives in the Android Keystore and cannot be exported, so until now there was no recovery:
a lost phone meant new keys at every issuer. AC-018 asks for a recoverable restore that needs independent recovery
material and an integrity check, and that does not bring commit authority back with the secrets.

## Decision

1. **The recovery material is a code, not the phone.** Making a backup shows a fresh recovery code (25 symbols of
   Crockford base32, 125 random bits) once; it is never stored. The backup file holds the stored keys with their
   label, provider and scope, and the git credentials with their tokens, sealed with AES-256-GCM under a key derived
   from the code (`PassphraseSeal`, PBKDF2-HMAC-SHA256, 210 000 rounds; the format's header is authenticated with
   it). The file and the code are kept apart by the user; neither alone opens anything. The export is journaled with
   the file's fingerprint, never its content.
2. **Integrity is the opening.** A wrong code, a changed byte or a file of another format does not open, and nothing
   is restored from a file that does not open. The screen shows what the file holds before anything is written.
3. **Restoring keeps what is there.** A key whose name is stored, or a git credential whose URL is held, is kept; the
   rest is written. A key's record and scope are written with its value, under the vault's lock, so a restored key is
   never stored without the limit on who may receive it.
4. **Secrets, not authority.** Restored git credentials can read but cannot publish until the user grants it again
   in the vault (`publishSuspended`); the commit gate refuses such a publish before anything is sent and says why
   (`WriteNotSent`). No promotion, capability, lease or fence is in a backup.

## Consequences

* A restored provider key is handed to sessions as its scope allows, as before; restoring is journaled
  (`vault.restored`, `git.credential_restored`).
* Verified: `VaultBackupTest` (the code's format and randomness, a loosely typed code, a wrong code, a changed byte,
  an evidence file refused as a backup, restoring keeps present values, the scope comes back, git credentials come
  back unable to publish until granted), `GitBrokerTest` (a publish refused before sending). On the emulator: a
  backup made through the system's save dialog (sealed, no token readable in it), the git credential revoked, the
  file opened through the system's picker (a wrong code refused, the right one typed in lower case without dashes),
  the credential restored as "Publishing suspended", a publish with it refused with "nothing was sent", and after
  "Grant publishing again" a publish with the restored token committed and read back.
