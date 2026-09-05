"""Reject distributable APK builds with missing public service configuration.

Checks presence/shape only. Live sign-in and inference remain separate release gates.
Never print configuration values.
"""
import os
import sys
from urllib.parse import urlsplit

REQUIRED = (
    'INTERPRETER_BACKEND_URL', 'FIREBASE_ANDROID_API_KEY',
    'FIREBASE_ANDROID_APP_ID', 'FIREBASE_PROJECT_ID',
    'FACEBOOK_APP_ID', 'FACEBOOK_CLIENT_TOKEN',
)

def problems(env):
    errors = [f'Missing repository variable: {name}' for name in REQUIRED
              if not env.get(name, '').strip() or env[name].strip().lower() in {'0', 'unconfigured', 'changeme'}]
    backend = env.get('INTERPRETER_BACKEND_URL', '').strip()
    if backend:
        try:
            url = urlsplit(backend)
            valid = (url.scheme == 'https' and bool(url.hostname) and
                     not url.username and not url.password and not url.query and not url.fragment)
        except ValueError:
            valid = False
        if not valid:
            errors.append('INTERPRETER_BACKEND_URL must be an HTTPS URL without credentials, query or fragment.')
    return errors

if __name__ == '__main__':
    errors = problems(os.environ)
    if errors:
        print('APK distribution blocked: account/AI service setup is incomplete.', file=sys.stderr)
        print('\n'.join(errors), file=sys.stderr)
        print('See docs/SECURE_AI_UPGRADE.md. No configuration values were printed.', file=sys.stderr)
        sys.exit(1)
    print('Required public configuration is present. This does not verify live sign-in or AI availability.')
