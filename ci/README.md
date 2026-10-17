# Sideload signing key

`sideload.keystore` is a **public, non-secret** signing key used for the sideloadable release
APKs. Passwords are `aw9sideload` (store and key), alias `aw9sideload`.

**Never use it for a Google Play upload.** It is committed deliberately so that CI and local
builds sign with the *same* certificate, which is what makes one release installable over the
previous one.

Before this key existed, `release` was signed with `signingConfigs.debug`. Gradle generates a
debug keystore on demand, so every CI runner produced its own key and **every release had a
different signing certificate**. Android then refused to update an installed app
(`INSTALL_FAILED_UPDATE_INCOMPATIBLE`), and the only way forward was to uninstall — which
discards the bridge token and the persisted step baseline.

To rotate this key you must accept that no installed build will upgrade again; bump the
version and document the uninstall requirement.
