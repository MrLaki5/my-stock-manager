<img src="docs/icon.png" alt="" width="96">

# MyStockManager

**Android app that turns a shoot into upload-ready stock photos.** It writes the title,
description and keywords into the JPEG itself, so the file you hand Adobe Stock or
Shutterstock already carries its metadata.

<br clear="left">

## What it does

- **Organises shoots into events** - one event per shoot, day or theme.
- **Imports straight into the album.** Picked images are copied to
  `Pictures/StockReady/StockReady - <event>/`, visible in your gallery and in every upload
  picker immediately. Your camera roll is never touched, and there is no second private copy
  to drift out of sync.
- **Generates metadata with OpenAI vision** - title, description, up to 49 relevance-ordered
  keywords, and a category. An optional hint adds what the model cannot see in the pixels,
  such as the place, subject or event; a place it names leads the caption.
- **Embeds it losslessly** into IPTC IIM (APP13) and XMP (APP1). Segments are spliced;
  pixels are never decoded or re-encoded.
- **Verifies every write.** The embedded copy is read back and compared before the album
  file is overwritten, so a failed embed can never damage an image you already have.
- **Lets you correct anything** - open an image to edit its title, description or category,
  and rename, reorder or remove an individual keyword. Saving rewrites the JPEG, not just
  the database.
- **Runs generation in the background**, one worker per image, so one failure retries on its
  own without holding up the batch.
- **Optionally syncs every event to NextCloud** - one folder per event under a path you
  choose (`/my-stock-manager` by default). New and re-keyworded images are uploaded, renames
  move the folder, and deletions made while sync is on are deleted on the cloud too. It is
  one-way, Wi-Fi only by default, and never touches files it did not upload itself.
- **Pulls everything back from NextCloud on request** - after reinstalling, or on a new
  phone, one tap downloads every event and image, with its title, description and keywords
  read back out of the file. It never deletes anything on the phone.
- **Keeps your API key and NextCloud app password in EncryptedSharedPreferences**,
  Keystore-backed and excluded from backup.

## Uploading stays manual

MyStockManager never connects to Adobe Stock or Shutterstock. It has no agency login, stores no
agency credentials, and does not submit, schedule or automate uploads. It talks to the OpenAI API,
for metadata, and, only if you set up sync, to your own NextCloud server, as a backup copy of
your events.

All it prepares is files. Each event is its own folder under `Pictures/StockReady/`, so when you
open an agency's upload form and add files, the system picker already shows that shoot grouped
together. You pick the images and submit them yourself. The title, description and keywords are
already embedded in each file, so it works like uploading photos you keyworded in Lightroom or
Bridge.

The photos are never generated or altered. A model only drafts the text, and you can edit every
field before it is written. It is still up to you to check that the text describes each image
accurately.

## Keyword limits

Adobe Stock caps keywords at 49, Shutterstock at 50 with a minimum of 7. The app clamps to
49 so one keyword set satisfies both, and warns below 7.

## Build

Requires an OpenAI API key, entered in Settings - none is bundled. Model is selectable
between `gpt-4o-mini` (default) and `gpt-4o`.

```bash
./gradlew installDebug
```

Kotlin · Jetpack Compose · Room · Hilt · WorkManager · Commons Imaging + Adobe XMPCore ·
minSdk 29

## Release

Release builds are signed with an upload key that is never committed. Google Play re-signs
the app with its own app-signing key.

**One-time setup.** Create the upload key in the gitignored `keys/` folder, and back it up
outside the repo together with its password. Without it you cannot publish updates.

```bash
mkdir -p keys
keytool -genkeypair -v -keystore keys/mystockmanager-upload.jks \
  -alias upload -keyalg RSA -keysize 4096 -validity 10000
cp keystore.properties.example keystore.properties   # then fill in the password twice
```

**Local build:** `./gradlew bundleRelease` writes `app/build/outputs/bundle/release/app-release.aab`,
which is the file you upload to Play. Without `keystore.properties` the build is produced
unsigned.

**CI build:** pushing a tag equal to `versionName` (a leading `v` is allowed) builds a signed
AAB and APK and attaches both to a GitHub release. Add these repository secrets first:

| Secret | Value |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | `base64 -w0 keys/mystockmanager-upload.jks` |
| `RELEASE_STORE_PASSWORD` | keystore password, only the password itself |
| `RELEASE_KEY_ALIAS` | `upload` |
| `RELEASE_KEY_PASSWORD` | the same password (PKCS12 keys share it) |

Before each release, bump both `versionCode` (Play rejects a code it has seen before) and
`versionName` in `app/build.gradle.kts`.
