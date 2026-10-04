# Security policy

## Reporting a vulnerability

Please do not open a public issue for security problems. Report them
privately instead: on the repository's **Security** tab, choose
**Report a vulnerability**.

Only the newest release receives fixes. Glacier updates itself, so a fix
reaches everyone with the next release.

## Verifying a download

Every release APK is built by the `Release` workflow from the tagged sources
and carries a signed build provenance attestation. With the GitHub CLI:

```sh
gh attestation verify glacier-androidtv.apk --repo Glacier-Jellyfin/glacier-androidtv
```

The in-app updater checks the SHA-256 digest GitHub publishes for the file,
the package name and the version before installing. Android then refuses any
update that is not signed with the same key as the installed app.
