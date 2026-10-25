# Umbra privacy policy

Effective date: 2026-10-25.

Umbra is an open-source Android app maintained by 鄭品彥
([chengmatt416](https://github.com/chengmatt416)). This policy covers the app and the project website
at https://umbra-agent.pages.dev. It describes the current source code; third-party services and
modified builds may behave differently.

## Data the app handles

To perform tasks you request, Umbra can process your prompts, files, code, terminal output, app names,
screen text, screenshots and device logs. Optional access includes notifications, SMS and linked
message conversations, replies, attached media, microphone input through speech recognition,
and commands/results from computers you pair as execution nodes. Screen and notification access can
expose other people's personal data and private information from other apps. Enable only the access
you need and have permission to use.

Sessions, task history, agent memory, settings and approval/security records are stored on the device.
API keys and connector secrets are encrypted using Android Keystore-backed keys; this does not mean
all workspaces, transcripts or screenshots are encrypted by Umbra. Android cloud backup is disabled
for the app. Root access, device compromise, exported files and files in shared folders have separate risks.

## What leaves your device

- **Your selected model providers or custom endpoints:** prompts and context needed for the task can
  include screen text, screenshots, files, tool results, message content and conversation history.
  Credentials are sent as required for authentication. Retention, training, processing location and
  charges depend on the provider and your account settings. A local Linux environment does not make
  a remote model local. Review the provider's policy before sending sensitive data.
- **Connections you enable:** Telegram uses Telegram's Bot API, iMessage connections use your configured
  BlueBubbles/OpenBubbles server, and X connections use X's API. Android notification replies and SMS
  send through the relevant app or carrier. Those services and message recipients receive content
  you send. Automatic replies can run when you explicitly enable them for an eligible conversation.
- **Paired execution nodes:** selected job files, commands and results move between the phone and the
  paired computer. They can remain in that computer's job folders or logs after unpairing.
- **Project hosting:** the updater periodically requests `/update.json` and can download APKs; the
  model directory requests `/models.json` from the project site. Cloudflare processes ordinary web
  request information, including IP address, requested URL and request headers. These requests do
  not need your task transcripts or API keys. Update controls are in Settings → About.
- **Downloads and agent activity:** optional Laya setup contacts package/model hosts such as PyPI and
  Hugging Face. Linux packages, sign-in flows, browsers, tools and commands may contact other sites.
  The destinations and transmitted data depend on the task and installed software.
- **Speech input:** the Android speech-recognition service you use may process audio remotely under
  that service's own policy.
- **Usage counts (you can switch them off):** on new installations from version 0.6.1, Settings → Share
  usage counts is on by default and shown with its switch during setup; installations from earlier
  versions keep the choice they had (off unless you turned it on). When enabled, opening
  the app sends a random installation identifier to `/api/usage-counts` at most once per UTC month
  after a successful report. Failed reports may retry after six hours. The endpoint stores a hash for
  the installation count and a different, month-specific hash for monthly active installations. It
  does not store the raw identifier, prompts, messages, files, screen contents, device logs or account
  details. Cloudflare still processes ordinary web request information as described above. Turning
  the setting off stops future reports; existing aggregate counts remain. The local identifier is
  removed when app-private storage is cleared or the app is uninstalled.

The app source does not include an advertising or automatic crash-reporting SDK. Its usage
counter is limited to the install and monthly-active measurements described above. This does not
describe telemetry in every installed tool or third-party service. The maintainer
does not operate the model providers or receive task conversations through an Umbra conversation server.
If you submit an issue, log or screenshot to the project, the hosting platform and maintainers can read it;
public submissions are visible to everyone. Remove credentials and personal information first.

## Website

The website uses browser local storage (`umbra-lang`) to remember your language choice.
It loads fonts from Google Fonts, so Google receives the font requests and associated request metadata.
There is no analytics script in the website source. Cloudflare hosts the pages, clips and downloads and
may process request/security information under its own policies and hosting configuration.
The repository does not establish a fixed retention period for hosting-provider logs.
See [Cloudflare's privacy policy](https://www.cloudflare.com/privacypolicy/) and
[Google Fonts privacy information](https://developers.google.com/fonts/faq/privacy).

## Retention and your controls

Task history, workspaces and memory can persist until you remove them. Message capture has retention
controls in the app; do not assume the same schedule applies to task transcripts, attached files,
exports or provider logs. Use Settings → Stop and take back to stop tasks, revoke helper/screen access
and delete history using the individual controls. You can remove keys, disconnect message connections,
revoke paired nodes and revoke Android permissions. Clearing Android app storage or uninstalling
removes app-private data, but does not reliably remove external workspaces, shared files, exports,
paired-computer data or copies already held by providers and recipients. Delete those separately.

For questions about this policy or requests concerning information you supplied to the maintainers,
contact the maintainer through the [project's GitHub profile](https://github.com/chengmatt416).
Use a private contact method listed there for sensitive information; do not put it in a public issue.
Provider-held data requests must also be directed to the relevant provider. Applicable statutory
rights are unaffected. Material policy changes will update the effective date in this file.
