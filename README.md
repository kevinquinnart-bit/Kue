# KueVault

One safe home for your files, across the storage you own: Google Drive (any number of accounts), your home computer's shared folder, and later OneDrive and Dropbox. Each section (Photos, Music, Films, Documents, My things, Backups) lives in one place you choose; KueVault never splits a section across accounts to get round storage limits. It sees only the files it puts in each place's KueVault folder.

My things keeps a file for everything you own: its receipt or invoice, warranty, manual and photos. Receipts and labels are scanned and read on the phone (make, model, serial number, shop, date, price, warranty); a product barcode is looked up in Open Products Facts; Amazon's order history (from "Request your data") brings in past orders. Manuals can be asked questions, typed or spoken, and the answer (the manual's own words, with its page) read out. A warranty reminder comes 30 and 7 days before it ends.

The home-computer test runs against a real shared folder when one is given: KUEVAULT_SMB=host/share/user/password.

Every push to the `kuevault` branch builds the app and publishes it as `KueVault.apk` on the `kuevault` download:
https://github.com/kevinquinnart-bit/Kue/releases/download/kuevault/KueVault.apk
