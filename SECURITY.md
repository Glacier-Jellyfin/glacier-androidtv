# Security policy

## Reporting a vulnerability

Please do not open a public issue for security problems. Report them
privately instead. Use this link:
https://github.com/Glacier-Jellyfin/glacier-androidtv/security/advisories/new

You can also open the repository's **Security** tab and choose
**Report a vulnerability**.

Reports usually get an answer within a few days. Glacier is a hobby project,
so there is no fixed deadline for a fix. Reports are disclosed together with
the fix.

## Supported versions

Only the newest release receives fixes. Glacier updates itself, so a fix
reaches everyone with the next release.

## Verifying a download

Every release APK is built by the `Release` workflow from the tagged sources
and carries a signed build provenance attestation. With the GitHub CLI:

```sh
gh attestation verify glacier-androidtv.apk --repo Glacier-Jellyfin/glacier-androidtv
```

The same attestation is attached to each release as
`glacier-androidtv.apk.intoto.jsonl`. Download it next to the APK to verify
offline:

```sh
gh attestation verify glacier-androidtv.apk \
  --bundle glacier-androidtv.apk.intoto.jsonl \
  --repo Glacier-Jellyfin/glacier-androidtv
```

The in-app updater checks the SHA-256 digest GitHub publishes for the file,
the package name and the version before installing. Android then refuses any
update that is not signed with the same key as the installed app.
