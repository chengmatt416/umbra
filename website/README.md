# Umbra website

Static, bilingual (English `/en/`, 繁體中文 `/zh-tw/`) landing page on **Cloudflare Pages**; the APK is served from **Cloudflare R2**.

The build also publishes `/privacy/`, `/terms/`, `/policy/` and `/licenses/` from the repository's legal documents,
with original license/notice files under `/legal/`. Both language footers link to these English documents.
Privacy and terms use readable headings, paragraphs and lists. Edit `../PRIVACY.md` and `../TERMS.md`
to update their content, then run `node build.mjs` and `node --test tools/legal-pages.test.mjs`.
Before announcing binary downloads, complete the outstanding requirements in
[`docs/RELEASE_LEGAL_REVIEW.md`](../docs/RELEASE_LEGAL_REVIEW.md), including matching source distribution;
publishing the policy pages alone does not clear the APK's bundled components.

```
src/content.mjs   all copy, both languages (edit here)
src/site.css, src/story.js   styling; the scroll story (the phone plays the clip of the step in view)
build.mjs         node build.mjs  ->  dist/   (no dependencies)
public/           favicon, og.png, clips/*.mp4|jpg (cut from real emulator recordings: ../marketing/clips.py)
functions/download/[[path]].js   /download/<umbra-x.y.z.apk> streams from the R2 bucket (Range supported); /download/latest redirects
tools/upload-release.mjs         big-file upload to R2 (multipart through a temporary Worker, deleted afterwards)
wrangler.toml     Pages project umbra-agent, R2 binding RELEASES -> bucket umbra-releases, LATEST_APK
```

Share **https://umbra.ideastone.tech/download/latest** as the permanent APK download link
(also available at https://umbra-agent.pages.dev/download/latest).
It redirects without caching to the release named by `LATEST_APK` in `wrangler.toml`, preserving
the versioned filename and resumable downloads. Both website download buttons use this URL.
For each release, update `LATEST_APK` alongside `src/apk.json` and deploy the site as described below;
uploading an APK to R2 alone does not change which release this link serves.

## Publish a new APK

```bash
source ../scripts/env.sh
export UMBRA_KEYSTORE=~/.umbra-signing/umbra-release.jks
export UMBRA_KEYSTORE_PASSWORD="$(cat ~/.umbra-signing/password.txt)"
export UMBRA_KEY_PASSWORD="$UMBRA_KEYSTORE_PASSWORD"
# the Linux image to carry: build/rootfs/umbra-rootfs-<v>-arm64.tar.xz + packs + manifest.json (runtime-build/rootfs/build.sh <v>);
# 0.4.0 includes the offline Chromium pack. A different image makes installed apps offer a Linux update.
(cd .. && ./gradlew -Pumbra.linuxVersion=0.4.0 -Pumbra.requireReleaseSigning=true :app:assembleRelease)
node tools/upload-release.mjs ../app/build/outputs/apk/release/app-release.apk umbra-0.4.3.apk   # writes src/apk.json (size, sha256, version, versionCode)
# bump version/file in src/content.mjs (APK.version/versionCode/file) and LATEST_APK in wrangler.toml, then:
node build.mjs && npx wrangler pages deploy dist --project-name umbra-agent --branch main
node tools/verify-release.mjs  # checks feed, ranges, redirect and full published APK SHA-256
```

The uploader passes its temporary credential to Wrangler over stdin, uploads in parts and removes its Worker
afterwards. Before announcing a release, verify the published APK's SHA-256, signing identity and `update.json`.

Releases must be signed with the same key forever (Android refuses updates signed with another one): back up `~/.umbra-signing/`.
Bump `versionCode` in `app/build.gradle.kts` for every release: installed apps update themselves from `dist/update.json`
(written by `build.mjs` only when `src/apk.json` describes `umbra-<version>.apk`), and only a higher `versionCode` is an update.

### Automatic GitHub releases from R2

`.github/workflows/r2-release.yml` checks the R2 bucket every five minutes (GitHub schedules can be delayed).
Starting with 0.6.1, every completed `umbra-X.Y.Z.apk` / `umbra-X.Y.Z.apk.sha256` pair becomes a `vX.Y.Z`
GitHub release. Uploading with `tools/upload-release.mjs` already writes both objects. No site deployment or
Android rebuild is needed. Other bucket objects and older APKs are ignored. Commit the matching source to
`main` before uploading; newly created release tags target the workflow's source commit.

The read-only `tools/r2-release-feed/` Worker lists only those APK/checksum pairs and streams those files.
It never exposes usage-count records or accepts writes, and holds no GitHub or Cloudflare API credentials.
GitHub Actions uses its built-in `GITHUB_TOKEN` with `contents: write`. The workflow pins downloads to R2
ETags, verifies the complete APK size and SHA-256, uploads both assets to a draft, then publishes it.
Interrupted draft uploads resume on the next run. Published versions are immutable: a changed APK requires
a new version. Existing matching releases are skipped, without downloading the APK again.

Deployment/setup (the production Worker and repository variable are already configured):

```bash
# From the repository root:
wrangler deploy --config website/tools/r2-release-feed/wrangler.jsonc
gh variable set R2_RELEASE_FEED_URL --repo chengmatt416/umbra --body https://<deployed-worker>.workers.dev
gh workflow run r2-release.yml --repo chengmatt416/umbra -f version=0.6.1
```

The workflow must exist on the default branch (`main`) for scheduled runs. The optional manual `version`
input limits a run to one completed pair; leaving it blank checks all versions since 0.6.1. The existing
tag-triggered `release.yml` remains available for full rebuilds; R2 synchronization does not push tags with
a personal token or trigger that build. Test the sync and Worker with:

```bash
node --test scripts/publish-r2-release.test.mjs website/tools/r2-release-feed/worker.test.mjs
```

To test an update end to end without touching the live site: build a later version (`-Pumbra.versionCode=… -Pumbra.versionName=…`,
`-Pumbra.bundleLinux=false` keeps it small), upload it under a test name, deploy a preview (`--branch ota-test`) whose
`update.json` points at it, and on the phone `adb shell setprop debug.umbra.update_url https://ota-test.umbra-agent.pages.dev/update.json`.
Delete the test objects (`npx wrangler r2 object delete umbra-releases/<name> --remote`) and the preview deployments afterwards.

## Re-record the clips

`marketing/capture/*.py` drive the real app on the emulator (`scripts/emulator.sh start`, `scripts/dev-device.sh setup`) and screen-record it;
`marketing/clips.py` cuts `marketing/clips.json` into `public/clips`; `marketing/reels/build_reel.py` builds the two Instagram Reels
(`marketing/reels/out/umbra-reel-{en,zh-tw}.mp4`, 1080×1920). The language model in the recordings is the project's scripted stand-in (`scripts/scripted_model.py`).

## Secure input links

`public/.well-known/assetlinks.json` associates `umbra-agent.pages.dev` with Umbra's existing release
signing certificate. `public/credential-handoff/` is a static, bilingual native-app launcher. It accepts
an opaque panel nonce only in a URL fragment, removes that fragment from browser history, contains no
credential fields and opens a package-bound Android intent only after a user click. No nonce or
credential is sent to this site's backend. The app emits HTTPS panel links only after Android reports
this domain verified and link handling enabled; otherwise its existing local link remains available.

Run `node tools/test-credential-links.mjs` after building. A live deployment and Android's actual
`pm verify-app-links`/`pm get-app-links` verification are required before describing HTTPS routing as
verified. Keep the association tied to the APK signing certificate; do not add a development key.

## Optional app usage counts

`POST /api/usage-counts` accepts only `{"install_id":"<random UUID v4>"}` (at most 256 bytes).
It uses the existing private `RELEASES` R2 binding, with no additional service or client SDK.
Objects under `usage-counts/v1/installs/` count distinct opted-in installations; objects under
`usage-counts/v1/monthly/YYYY-MM/` count installations that opened the main app during that UTC month.
Count objects with each prefix to obtain installs and MAU; MAU is an installation-based measure.
Retries use conditional creates and cannot increase either count twice. Identifiers are SHA-256
hashed with separate install/month namespaces; only the hash keys and the value `1` are stored.
The public download handler rejects these object paths. There is no public reporting/read endpoint.

Collection is off by default and controlled by Settings → Share usage counts. Background service
wakes never report activity. The app retains the same random ID across opt-out/re-enable, and a
successful report suppresses more requests that month. Uninstalling or clearing app storage creates
a new installation. These counts are not device attestation; an unauthenticated client can submit
synthetic identifiers. Hosting-provider request logs have their own retention/configuration.

Verify with `node --test tools/usage-counts.test.mjs` and `wrangler pages functions build`.
The endpoint was deployed and checked on 2026-10-24, including duplicate synthetic reports and private
R2 record verification/cleanup. The scoped deployment preserved the existing live assets and APK feed;
only the two privacy documents changed. Publishing the entire working-tree site also publishes any
other pending content/download changes; review those separately before a production Pages deployment.
