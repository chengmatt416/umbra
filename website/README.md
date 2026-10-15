# Umbra website

Static, bilingual (English `/en/`, 繁體中文 `/zh-tw/`) landing page on **Cloudflare Pages**; the APK is served from **Cloudflare R2**.

```
src/content.mjs   all copy, both languages (edit here)
src/site.css, src/story.js   styling; the scroll story (the phone plays the clip of the step in view)
build.mjs         node build.mjs  ->  dist/   (no dependencies)
public/           favicon, og.png, clips/*.mp4|jpg (cut from real emulator recordings: ../marketing/clips.py)
functions/download/[[path]].js   /download/<umbra-x.y.z.apk> streams from the R2 bucket (Range supported); /download/latest redirects
tools/upload-release.mjs         big-file upload to R2 (multipart through a temporary Worker, deleted afterwards)
wrangler.toml     Pages project umbra-agent, R2 binding RELEASES -> bucket umbra-releases, LATEST_APK
```

## Publish a new APK

```bash
source ../scripts/env.sh
export UMBRA_KEYSTORE=~/.umbra-signing/umbra-release.jks UMBRA_KEYSTORE_PASSWORD=$(cat ~/.umbra-signing/password.txt) UMBRA_KEY_PASSWORD=$UMBRA_KEYSTORE_PASSWORD
(cd .. && ./gradlew :app:assembleRelease)
node tools/upload-release.mjs ../app/build/outputs/apk/release/app-release.apk umbra-0.2.0.apk   # writes src/apk.json (size, sha256)
# bump version/file in src/content.mjs (APK.version/file) and LATEST_APK in wrangler.toml, then:
node build.mjs && npx wrangler pages deploy dist --project-name umbra-agent --branch main
```

Releases must be signed with the same key forever (Android refuses updates signed with another one): back up `~/.umbra-signing/`.

## Re-record the clips

`marketing/capture/*.py` drive the real app on the emulator (`scripts/emulator.sh start`, `scripts/dev-device.sh setup`) and screen-record it;
`marketing/clips.py` cuts `marketing/clips.json` into `public/clips`; `marketing/reels/build_reel.py` builds the two Instagram Reels
(`marketing/reels/out/umbra-reel-{en,zh-tw}.mp4`, 1080×1920). The language model in the recordings is the project's scripted stand-in (`scripts/scripted_model.py`).
