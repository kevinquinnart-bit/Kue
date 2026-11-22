# KueTV

Kevin's own IPTV app for Android TV and Android phones (one app, it lays itself out for each), written from scratch. No code from any other IPTV player.

- `app/src/main/java/art/kquinn/kuetv/Provider.kt`: signing in to the provider and loading channels and films
- `PhoneSetup.kt`: typing the provider details on a phone instead of with the remote
- `MainActivity.kt`: the screens
- `Player.kt`: the player
- `Device.kt`: telling a TV from a phone
- `Updates.kt`: checking for a newer KueTV each time it opens, and putting it on
- `TvApps.kt`: the free UK and Irish TV apps (opens them, or the app store)
- `app/src/test`: tests against a pretend provider

Every push to the `main` branch builds the app and publishes it as `KueTV.apk` on the `kuetv` release. Each build is also put on the old `kuetv2` release as `KueTV2.apk`, so apps up to 0.1.156 still find it.
