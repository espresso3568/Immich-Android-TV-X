# Immich TV Plus attribution and modification notice

Immich TV Plus is an unofficial modified Android TV application. It is not an
official release or an endorsement by the upstream projects.

## Upstream sources and licenses

- Original application: [giejay/Immich-Android-TV](https://github.com/giejay/Immich-Android-TV).
- Immediate base: [xyzhong987/Immich-Android-TV-X](https://github.com/xyzhong987/Immich-Android-TV-X),
  commit `037cb7ce0d94f36113afd0bec19b84f23ec705ed` (6.2.0 / 148).
- Application license: GNU General Public License version 3, reproduced in [LICENSE](LICENSE).
- Vendored player: [giejay/MediaSliderView](https://github.com/giejay/MediaSliderView),
  retaining its Apache License 2.0 in [mediaslider/LICENSE.md](mediaslider/LICENSE.md).
- Existing copyright, attribution, and license notices in the sources remain in place.
  This notice does not replace individual dependency licenses or grant trademark rights.

## Modifications dated 2026-10-20

The Immich TV Plus version changes the app name and application ID to
`com.jerry.immichtv.plus`, adds complete multi-album screensaver shuffle rounds,
adds optional shuffle for album slideshows, reshuffles after each complete round,
and fixes authentication state retained after signing in again.

The vendored player changes add shuffle-round configuration and navigation,
round-boundary handling, loading and cancellation handling, and video-player cleanup.
Changed upstream player files carry individual modification notices.

Independent release signing, optional Firebase initialization, Windows build and
Android TV emulator tools, tests, and Traditional Chinese instructions are also added.
See [docs/implementation-plan.md](docs/implementation-plan.md) for implementation details.

The combined application is provided under GPLv3, without warranty as described in
LICENSE. The vendored player's existing Apache 2.0 terms and attribution are preserved.
