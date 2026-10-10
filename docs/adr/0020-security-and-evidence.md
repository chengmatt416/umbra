# 0020. Security & Evidence

Status: accepted. Architecture spec §13.

## Context

The spec asks for evidence-tiered incident response, an exposure graph, an incident state machine
(Detected → Triaged → Contained → Investigating/Remediating → Trust restoration → Resolved, where resolving resumes
nothing) and privacy-tiered evidence custody: local journals, independent digest anchoring, and encrypted export.
It is explicit that a local hash chain alone does not stop an attacker with full write access.

## Decision

1. **Every control journal's chain is checked** (`SecurityEvidence.check`), with one combined digest over all of them.
   The screen also checks that no stored secret value appears in any journal (values are decrypted in memory only for
   that comparison).
2. **Independent anchoring**: the digest and each journal's head (sequence and digest) are sent to a paired node you
   choose, which appends them to `anchors.jsonl` in its own folder, keyed by the phone's certificate. Verification
   reads the node's copy back and requires every anchored entry to still be in the phone's journals with the same
   digest: a rewritten or removed history shows even when the local chain was rebuilt to look intact. Only digests
   leave the phone.
3. **Exposure map**: credentials and their hand-overs (from the vault), nodes with their jobs and the bytes sent, the
   commit gate's published writes. "Potentially exposed" means handed to a process, not leaked.
4. **Incidents are recorded by the user** with an evidence level (L1–L4) and the trust boundary concerned; their
   state moves only when the user moves it, each change is a new version (its own sequence), and each points at the
   controls to use (vault, commit gate, nodes, emergency lockdown). Nothing is contained automatically; a false
   positive is closed with its reason and undoes nothing by itself.
5. **Encrypted export**: every journal with its chain check, the incidents and the anchors, as JSON, under AES-256-GCM
   with a PBKDF2 (210 000 rounds) key from a passphrase of at least ten characters, saved where the user chooses.
   `umbra-node evidence-decrypt FILE` reads it back; a wrong passphrase or a changed file is refused.

## Consequences

* Containment remains the user's acts through existing controls; automated containment is not part of this.
* Verified: `SecurityEvidenceTest` (tampering, an anchor catching a forged chain and a removed journal, the incident
  machine and versions, export roundtrip with wrong-passphrase and tamper refusal), `NodeEndToEndTest` (anchors kept
  per phone); on the emulator: 9 journals intact with the secret scan clean, a digest anchored on `umbra-node` on a Mac
  and verified against its copy (9 intact, 0 rewritten, 0 missing), an L2 git incident recorded and triaged, the
  evidence exported through Android's file picker and decrypted on the Mac (10 journals, the incident, the anchor;
  the wrong passphrase refused; the test key's value absent).
