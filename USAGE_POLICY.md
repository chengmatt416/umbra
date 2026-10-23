# Preview use and third-party services

Umbra is a preview tool that can run commands and operate your phone. Model output and action
classification can be wrong; review sensitive work, keep backups and grant only necessary access.
Approval controls reduce risk but are not a guarantee against unintended actions. Root, Shizuku,
ADB and accessibility access are powerful permissions. See [the runtime limits](docs/adr/0010-what-the-agent-can-reach.md).

Use only devices, accounts, files and conversations you are authorized to access. Obtain consent
where required before capturing, processing or sending another person's messages, screenshots,
voice or personal data. Respect applicable law and the terms of the services and apps you connect.
Umbra does not grant permission to bypass access controls or platform restrictions.

Model providers, OAuth/subscription sign-in, Telegram, X and other connectors have separate terms,
rate limits, availability and fees. Support in the code does not establish that a particular account,
subscription or automated use is authorized by its provider. Use a permitted API or sign-in method
and review the current provider terms. Umbra is independent of those providers.

The source code is available under [MIT](LICENSE), subject to third-party component licenses.
The warranty and liability provisions are in that license and remain subject to applicable law.
This notice adds no restrictions to the software licenses and is not a promise of security,
fitness, service availability or legal compliance. See [privacy](PRIVACY.md) and
[third-party notices](THIRD_PARTY_NOTICES.md) before connecting sensitive data.
