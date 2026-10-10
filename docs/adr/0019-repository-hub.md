# 0019. The Repository Hub

Status: accepted. Architecture spec §2.1, §3.2.

## Context

A repository is its own entity: not a project and not a session. The spec makes a global Repository Hub the
authoritative entry point, gives each repository an integration policy, links repositories to projects (many to
many), and asks that the difference between worktrees, shared refs and independent clones be shown to the user.

## Decision

1. **`RepositoryHub` (core/control)** records repositories by their canonical location (a checkout in the Linux
   environment's workspace), where they were cloned from, their integration policy and the projects linked to them.
2. **Register or clone.** Checkouts found under `/workspace` can be registered; cloning takes only URLs git names
   (`https://`, `ssh://`, `git@host:path`, a local path), refuses one starting with a dash (so it cannot become an
   option) and anything else (such as `ext::`), and passes `--` before the URL. Folder names are plain names.
3. **Preflight**: it is a repository, its branch and HEAD, whether the working tree is clean, whether each remote
   answers (`ls-remote`), its packed size. The result is kept and journaled.
4. **Integration policy**: fast-forward only by default; "rewrite with its own permission" lets the commit gate
   publish a rewrite only with that promotion's own permission. The commit gate asks the hub; an unregistered
   repository is treated as "with permission".
5. **Forgetting** removes the record, never the checkout.
6. Umbra makes no worktrees or shared-ref checkouts; the screen says so and explains that a second clone is an
   independent copy publishing through the same gate.

## Consequences

* Verified with real git on the host (`RepositoryHubTest`: clone, preflight clean and dirty, refused URLs and names,
  the commit gate refusing a rewrite under fast-forward only and allowing it under the other policy) and on the
  emulator (a workspace checkout registered and preflighted, a repository cloned into `/workspace/repos`).
