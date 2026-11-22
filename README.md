# KueTV VPN engine

The recipe for the VPN part of KueTV. It builds OpenVPN for Android (by Arne Schwabe, GPL v2) as a library
and puts it on the `vpn-engine` download. KueTV keeps a copy in `app/libs/openvpn-engine.aar`.

- `.github/workflows/vpn-engine.yml`: gets OpenVPN for Android at a fixed version and builds the library
- `tools/to_library.py`: turns its app project into a library project

Every push to this branch rebuilds the library.
