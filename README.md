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
- **Generates metadata with OpenAI vision** - title, editorial caption, up to 49
  relevance-ordered keywords, and a category. You can add a location, which the model cannot
  infer from the pixels and which buyers search by.
- **Embeds it losslessly** into IPTC IIM (APP13) and XMP (APP1). Segments are spliced;
  pixels are never decoded or re-encoded.
- **Verifies every write.** The embedded copy is read back and compared before the album
  file is overwritten, so a failed embed can never damage an image you already have.
- **Lets you correct anything** - open an image to edit its title, description or category,
  and rename, reorder or remove an individual keyword. Saving rewrites the JPEG, not just
  the database.
- **Runs generation in the background**, one worker per image, so one failure retries on its
  own without holding up the batch.
- **Keeps your API key in EncryptedSharedPreferences**, Keystore-backed and excluded from
  backup.

## Caption format

The description is always written as the editorial caption both agencies expect:

```
Belgrade, Serbia - May 23, 2026: Large Serbian national flags wave above crowds
marching toward Slavija Square during a student-led anti-government rally.
```

The model writes only the body; the app prepends the lead, so the shape is guaranteed
instead of merely requested. The location comes from the event and the date from the
photo's EXIF. Either part drops out when unknown - a photo with no capture date gets no
date rather than a guessed one, because that would be a factual claim about when something
happened.

The editor holds the body, and shows the assembled caption beneath it so you can see what
an agency will actually receive. Titles are separate and stay short: IPTC caps them at 64
characters.

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
