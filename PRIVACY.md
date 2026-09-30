# Privacy Policy

**MyStockManager**, Android app
Effective date: 30 September 2026

MyStockManager helps you prepare your own photos for stock agencies by writing a title,
description and keywords into each file. This policy explains what data the app handles,
where that data goes, and how to delete it.

## Summary

- The developer does not collect, receive or store any of your data. The app has no
  developer servers, no analytics, no advertising and no account.
- The app sends a photo to OpenAI only when you choose that photo and tap Generate.
- If you turn on sync, the app uploads your photos to the NextCloud server you set up.
- Your OpenAI API key and NextCloud app password are stored encrypted on your device.

## Data the app handles

### Photos you import

The photos you pick are copied into `Pictures/StockReady/` on your device. The app does
not open your camera roll or any other photos unless you pick them.

### Photos and hints sent to OpenAI

When you select images and tap Generate, the app sends each selected image to the OpenAI
API (`api.openai.com`) to draft its title, description and keywords. Before sending, the
app shrinks the image to at most 1024 pixels on its longest side and re-encodes it. This
removes its EXIF metadata, including GPS location and camera details. Any hint you type,
such as a place or subject, is sent along with the image.

These requests are made with your own OpenAI API key, directly from your device to OpenAI.
The developer never sees them. OpenAI's handling of this data is covered by its own terms
and privacy policy: <https://openai.com/policies/privacy-policy>.

### Photos and metadata synced to NextCloud (optional)

Sync is off by default. If you connect a NextCloud server and turn sync on, the app
uploads your events and images to the folder you choose on that server. It also renames
or deletes items there when you rename or delete them in the app. Pull downloads them
back to your phone. The app only connects to the server address you enter, and only over
HTTPS. You or your server's operator control that server.

### Credentials

Your OpenAI API key, NextCloud server address, login and app password are stored in
Android's EncryptedSharedPreferences, protected by a key in the Android Keystore. They
are excluded from Android backups and device transfers. The API key is sent only to
OpenAI, and the NextCloud credentials only to your NextCloud server.

### App data on your device

Event names, titles, descriptions, keywords and hints are stored in a database on your
device. If Android backup is on for your device, this database may be included in your
Google account backup, as it is for most apps. The credentials above are never included.

## What the app does not do

- It does not sell or share your data with anyone.
- It does not use analytics, crash reporting or advertising SDKs.
- It does not request location, contacts, camera or storage permissions. Its only
  permission is internet access.
- It does not connect to Adobe Stock, Shutterstock or any other agency. You upload
  files to them yourself.

## Security

All network traffic uses HTTPS. The app rejects NextCloud server addresses that are not
HTTPS.

## Keeping and deleting your data

- To delete images and events, delete them in the app. Deleting an image also removes
  its file from `Pictures/StockReady/`.
- Uninstalling the app deletes its database and stored credentials. Photos already saved
  in `Pictures/StockReady/` stay on your device until you delete them.
- To remove data from OpenAI, see OpenAI's data controls for your API account.
- To remove data from NextCloud, delete the app's folder on your server. By default this
  is `/my-stock-manager`.

## Children

The app is not directed at children and is not meant for users under 18.

## Changes

If this policy changes, the updated version will be published at this address and the
effective date above will change.

## Contact

Questions about this policy: Milan Lazarević, <milan.lazarevic.dev@gmail.com>
