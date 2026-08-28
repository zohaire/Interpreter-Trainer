# Google Play production handoff

The repository can build a minified, resource-shrunk APK and Android App Bundle. Publishing a production listing still requires an enrolled Google Play Console developer account, completed identity/payment steps, store declarations and a private signing identity that must never be committed to Git.

## Production build

Create or obtain the upload key through the Play Console workflow, keep it in a secrets manager, and provide:

- `INTERPRETER_RELEASE_KEYSTORE`
- `INTERPRETER_RELEASE_STORE_PASSWORD`
- `INTERPRETER_RELEASE_KEY_ALIAS`
- `INTERPRETER_RELEASE_KEY_PASSWORD`
- optionally `INTERPRETER_VERSION_CODE` and `INTERPRETER_VERSION_NAME`

Then run:

```bash
./gradlew --no-daemon :app:testDebugUnitTest :app:lintRelease :app:bundleRelease
```

Upload `app/build/outputs/bundle/release/app-release.aab` to an internal testing track first. Use Google Play App Signing and retain the upload key securely.

## Listing and policy checklist

- Host the repository's `PRIVACY.md` at a stable public HTTPS URL and use that URL in Play Console.
- Complete Data safety declarations for microphone/audio, user-provided text, AI processing, Android speech services and Puter/provider behavior.
- Declare that AI feedback is advisory and not a certified exam result.
- Provide phone/tablet screenshots, icon, feature graphic, support email and release notes.
- Complete content rating, target-audience, ads and app-access declarations accurately.
- Test microphone denial, RTL Arabic, account authentication, data deletion and upgrades on the internal track.

The public GitHub preview key is intentionally unsuitable for Play production and must not be promoted to a private trust anchor.
