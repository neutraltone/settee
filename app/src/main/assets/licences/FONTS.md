# Settee's fonts

Settee bundles two typefaces, unmodified, alongside the app as an aggregate. They are licensed under the
SIL Open Font License 1.1, not the GPL: each family's `OFL.txt` sits beside this file, exactly as published.

The files are Google Fonts' copies from [google/fonts](https://github.com/google/fonts) at commit
`5bb28733cec313b63fbd415b574b5ae35c8aa8b8`, byte for byte. Young Serif is pinned at 3.003, because the
designer's repository has since redrawn it much thinner.

| File | Family | Version | Source | SHA-256 |
|---|---|---|---|---|
| `res/font/young_serif_regular.ttf` | Young Serif Regular | 3.003 | `ofl/youngserif/YoungSerif-Regular.ttf` | `42bb2602364b28569fdce5eeb6ab3d81cf8090ba81b211f1229ed69f33155523` |
| `res/font/alegreya_sans_medium.ttf` | Alegreya Sans Medium | 2.004 | `ofl/alegreyasans/AlegreyaSans-Medium.ttf` | `4b89fe7804fd1485ec2757795a53ffdb66e1206dd56f844c2d72b3c944815b43` |
| `res/font/alegreya_sans_bold.ttf` | Alegreya Sans Bold | 2.004 | `ofl/alegreyasans/AlegreyaSans-Bold.ttf` | `a3055a1893759bdbd7504bb22abc583769e7974c49353176eac0b03792c9fb8e` |
| `assets/licences/young-serif-OFL.txt` | Young Serif licence | 3.003 | `ofl/youngserif/OFL.txt` | `737d8d343b674c6894f373de71a044b6782c130f2aebf180933d63ddf7a3dc19` |
| `assets/licences/alegreya-sans-OFL.txt` | Alegreya Sans licence | 2.004 | `ofl/alegreyasans/OFL.txt` | `0677891e6a143f297350d260ad766ad33bfc18ed5fa4f213acf648d6b597ec1a` |

Upstream: [noirblancrouge/YoungSerif](https://github.com/noirblancrouge/YoungSerif) and
[huertatipografica/Alegreya-Sans](https://github.com/huertatipografica/Alegreya-Sans).

`FontFilesTest` fails if a file here stops matching its hash. To update a font, replace the file with the
published one and change its row, never edit the file itself.
