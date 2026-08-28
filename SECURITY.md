# Security Policy

## Supported version

Security fixes are applied to the latest release and the current `main` branch. Public preview builds are test releases and are not signed with the private Play production key.

## Report a vulnerability

Email **zohaireachak@gmail.com** with the subject `Interpreter Trainer security report`. Include the affected version, reproduction steps, impact and any suggested mitigation. Do not publish secrets, personal practice data or an unpatched vulnerability in a public issue.

You should receive an acknowledgement within seven days. The report will be assessed, fixed and disclosed proportionately to its impact.

## Security boundaries

Interpreter Trainer does not contain a developer-owned AI provider key. The online coach depends on Puter, Qwen and Android system services; outages or account-policy issues in those services are outside the app's direct control. The app does not bypass authentication, DRM or media-provider restrictions.
