# Sideload signing key

`hy-sideload.jks` signs the APKs built by CI so that each new build installs as an
**update** over the previous one (Android refuses updates signed with a different key).

It is committed on purpose, for personal sideloading only. Anyone with this repo could
sign an APK that installs over yours, so **before publishing on the Play Store** generate a
private key, store it as GitHub secrets, and point the workflow env vars at it.

Store/key password: `hyassistant`, alias: `hy`.
