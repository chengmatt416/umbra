# 0022. The git credential broker: a token the agent never holds

Status: accepted. Architecture spec §12, AC-015. Extends ADR 0016 (the vault) and ADR 0017 (the commit gate);
bounded by ADR 0010 (what the agent can reach).

## Context

Publishing to a private repository needs a token. Until now the commit gate pushed with whatever credentials the
checkout had in Linux, which the agent can read: it runs as the same user, in the same files. AC-015 asks that an
agent trying to read a high-privilege token cannot, and that brokered actions reach only the resources and operations
they were authorized for. ADR 0010 sets the limit: the agent shares the app's uid, so a token is safe from it only
where the agent cannot look, that is in root's processes inside the environment (whose `/proc` is mounted with
`hidepid=2`) and in the app's own non-dumpable process.

## Decision

1. **A credential is bound to one https URL and to operations** (`GitCredentials`, core/control): read (ls-remote,
   fetch) and publish (only through the commit gate). The URL is normalized (https only, no user, query or fragment);
   it is the whole of the credential's reach. The token lives in a separate keystore-encrypted store
   (`git-secrets`), so nothing that hands provider keys to sessions ever lists it. Every use goes into a hash-chained
   journal (`git-broker`), never the value.
2. **Used only by root, in a staging repository** (`GitBrokerScript`): a fresh repository in a root-only folder, with
   hooks off, no system or global configuration, other credential helpers cleared, objects checked, no redirects, and
   only https and local files allowed. The token arrives as the first line of the root command's stdin, from the app,
   so it is never in a command line, a file or an environment the agent can see; a credential helper inside that root
   process hands it to git. The target URL comes from the credential, never from a checkout's configuration.
3. **The candidate comes in as data.** The agent's git writes the validated commit into a bundle (only what the remote
   lacks); root copies it into the staging repository and checks that it holds exactly the validated commit before
   pushing with `--force-with-lease`. A checkout that moved, swapped the file or rewrote its configuration changes
   nothing that is published. Reading the remote into a checkout works the other way: root fetches into a bundle in a
   root-owned, read-only folder; the agent's git reads it as `refs/remotes/umbra-broker/<branch>`.
4. **The commit gate is unchanged.** `BrokeredGit` is its `GitPort`: a remote that is a credential's URL is read and
   written through the broker; any other remote goes to the checkout's own git as before. Promotions, leases,
   read-back and recovery work the same either way.
5. **The broker can be suspended as a whole** (AC-016): when the user no longer trusts it, one act stops every
   brokered use at once, persisted across restarts and journaled, until they resume it. Model keys and sessions are
   not touched. Revoking the tokens at their issuer stays the user's act, and the vault says so; nothing suspends or
   revokes by itself.
6. **Without root the broker refuses.** Under proot root is the agent's own user, so a token in "root's" process would
   be readable; the vault and the commit screen say so and nothing is run.

## Consequences

* Model provider keys are unchanged (ADR 0016): omp needs them in its environment.
* Revoking here deletes Umbra's copy only; the token stays valid at its issuer until revoked there, and the screen
  says so. Rotation, scopes and revocation are journaled, separate acts.
* Verified: `GitBrokerTest` against a real private server (git http-backend over HTTPS with Basic authentication): a
  hostile checkout (an `insteadOf` sending its pushes elsewhere, a pre-push hook, its own credential helper) cannot
  push by itself; the gate publishes through the broker and reads it back, the hook never runs, staging folders,
  bundles and temporary refs are gone, and the token is in no file of the control store; a moved remote is not
  overwritten; a wrong token fails without being shown; a read-only credential cannot publish; without root nothing
  runs; a suspended broker refuses every use and survives a restart until resumed. On the emulator against the same kind of server on the Mac (10.0.2.2): the agent's own ls-remote is refused
  for want of credentials; Fetch through the broker gives the checkout the remote's main; the agent's commit is
  proposed, authorized and published through the broker and read back; an agent-side scanner reading every
  `/proc/*/environ` and command line it can see, and `/tmp`, `/home`, `/workspace`, for 90 seconds across the push,
  found no token and no root process (the same scanner finds a planted token at once); the token is in none of the
  app's control files; with the broker suspended a fetch is refused with the reason while a model session completes,
  and after resuming the fetch works again.
