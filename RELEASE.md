# Releasing

Releases are published to Maven Central by `.github/workflows/release.yml` when a version tag is pushed:

```shell
git tag v0.1.0
git push origin v0.1.0
```

The workflow:

1. Sets the version from the tag. A tag like `v0.1.0` releases `0.1.0`; SNAPSHOT tags are rejected.
2. Runs `mvn -P release deploy`, which builds, tests, signs the jars and uploads them to Central. It waits until
   Central reports them as published.
3. Creates a GitHub release for the tag with generated notes and the jars attached.

Watch the run in the Actions tab. It can take 10–30 minutes after "published" before the artifact appears in
search at https://central.sonatype.com.

The version in `pom.xml` stays `-SNAPSHOT` on `main`; only the release build uses the tag's version.

## One-time setup (already done)

- The `tech.jaylu` namespace is verified on https://central.sonatype.com.
- The `trippletick` GitHub organisation has these Actions secrets, available to all its repositories:
  `CENTRAL_USERNAME`, `CENTRAL_PASSWORD` (a Central user token), `GPG_PRIVATE_KEY` and `GPG_PASSPHRASE`
  (the signing key, whose public half is on keyserver.ubuntu.com and keys.openpgp.org).

To try the signing and packaging locally without publishing:

```shell
mvn -P release verify -Dgpg.skip
```
