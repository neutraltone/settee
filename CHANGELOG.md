# Changelog

Settee is based on OpenTV 0.11.8; everything before this is in [OpenTV's changelog](https://github.com/opentvproject/opentv/blob/v0.11.8/CHANGELOG.md).

## [0.5.0](https://github.com/neutraltone/settee/compare/v0.4.0...v0.5.0) (2026-11-10)


### ⚠ BREAKING CHANGES

* **#101:** removing, renaming, unlocking, unstarring and unhiding behave properly across your devices (add your providers and Favourites again) ([#144](https://github.com/neutraltone/settee/issues/144))

### Features

* **#109:** changing channel shows a brief banner instead of the player controls ([#125](https://github.com/neutraltone/settee/issues/125)) ([daa2163](https://github.com/neutraltone/settee/commit/daa2163a17678e0d51ec39a3521ebd7ef3323b88))
* **#131:** Shows recommends series based on what you've watched ([#174](https://github.com/neutraltone/settee/issues/174)) ([244a3f9](https://github.com/neutraltone/settee/commit/244a3f9498811342228c7cb66d429d25d69de984))
* **#157:** Settee has its own app icon, TV banner and notification icon ([#190](https://github.com/neutraltone/settee/issues/190)) ([11c1013](https://github.com/neutraltone/settee/commit/11c101324fd10cae03164b6d304084eae8fdb831))
* **#158:** Settee gets its own warm colours and fonts, and is always dark ([#195](https://github.com/neutraltone/settee/issues/195)) ([42858ef](https://github.com/neutraltone/settee/commit/42858ef58039f6ebc48551b03caa6a15c127ff2d))
* **#52:** episodes you've watched fade back so the ones still to watch stand out ([#138](https://github.com/neutraltone/settee/issues/138)) ([de0ceee](https://github.com/neutraltone/settee/commit/de0ceeee6e7ed76ee0294d72d0c9d19c1ce2ed55))
* **#76:** channels have the same options wherever they appear ([#136](https://github.com/neutraltone/settee/issues/136)) ([be144ff](https://github.com/neutraltone/settee/commit/be144ff5dca4175345c82bb7170c62c34ff73364))
* **#77:** catch up on a series by marking episodes watched up to here, or a whole season ([#134](https://github.com/neutraltone/settee/issues/134)) ([2047186](https://github.com/neutraltone/settee/commit/20471866b058e20d05e7afbac8e3ce627298b829))
* **#78:** the Menu button opens options while watching ([#135](https://github.com/neutraltone/settee/issues/135)) ([444162e](https://github.com/neutraltone/settee/commit/444162ea3e0f65bd587904575ec25a83e5750ce3))
* **#79:** play from the start or show a title's info from its options ([#133](https://github.com/neutraltone/settee/issues/133)) ([5dafde0](https://github.com/neutraltone/settee/commit/5dafde07d3f5609258ef749e8721c8b6637d6f3c))


### Bug fixes

* **#101:** removing, renaming, unlocking, unstarring and unhiding behave properly across your devices (add your providers and Favourites again) ([#144](https://github.com/neutraltone/settee/issues/144)) ([d18710b](https://github.com/neutraltone/settee/commit/d18710b719a1c9c25bbcae1f6fbf978589d58fe8))
* **#102:** watch progress keeps syncing after you change your provider password, and reaches a show you haven't opened ([#123](https://github.com/neutraltone/settee/issues/123)) ([ab0962f](https://github.com/neutraltone/settee/commit/ab0962f58f883bb872394917f95288a7dde3f195))
* **#112:** text boxes keep the keyboard shut until OK, and its own input stays in step with what's typed ([#188](https://github.com/neutraltone/settee/issues/188)) ([77865be](https://github.com/neutraltone/settee/commit/77865bea8c7b360364e035ad6088ed44e6d5c91f))
* **#132:** the season and episode marker is easy to read on the episode you're highlighting ([#137](https://github.com/neutraltone/settee/issues/137)) ([4c1e706](https://github.com/neutraltone/settee/commit/4c1e706c123582f818be21db7f7ba786b8e3fb11))
* **#175:** the Stick is less busy after building the TV guide ([#179](https://github.com/neutraltone/settee/issues/179)) ([3751d92](https://github.com/neutraltone/settee/commit/3751d92559fc8d356a00506ea3ae6634f235f8f0))
* **#180:** a 4K title this device can't play falls back to another version, or says so with a way back ([#187](https://github.com/neutraltone/settee/issues/187)) ([e3c1cd3](https://github.com/neutraltone/settee/commit/e3c1cd33ed82b21e9cd06c3b9c7dcfea3152badd))
* **#182:** a stream too big for this device's memory says so, instead of failing with an error code after a minute ([#183](https://github.com/neutraltone/settee/issues/183)) ([6b0fbbb](https://github.com/neutraltone/settee/commit/6b0fbbb55e33425de7852bb4e403014ea04ed769))
* **#184:** a 4K stream, or one with sound this device can't play, says so instead of a slideshow, an endless spinner or silence ([#186](https://github.com/neutraltone/settee/issues/186)) ([07188fa](https://github.com/neutraltone/settee/commit/07188fabffa78f9985b162a858247200851a14af))
* **#196:** the address under the phone set-up QR code opens the form when typed into a phone ([#197](https://github.com/neutraltone/settee/issues/197)) ([06a806a](https://github.com/neutraltone/settee/commit/06a806a96a7b46e9e9aedacc51bd9baad50bb6c5))
* **#91:** Settee shows its icon and name on older Fire TV Sticks ([#124](https://github.com/neutraltone/settee/issues/124)) ([ff16095](https://github.com/neutraltone/settee/commit/ff1609566b74fca940e715cfae6bfc1f7cf01276))


### Performance

* **#148:** Settee starts faster and its screens move more smoothly once Android has compiled it ([#198](https://github.com/neutraltone/settee/issues/198)) ([b2e2d45](https://github.com/neutraltone/settee/commit/b2e2d45afa43539173a79c15d5958a4433d24a61))
* **#177:** Settee settles much sooner after you open it on a Fire TV Stick ([#178](https://github.com/neutraltone/settee/issues/178)) ([3b8b3cd](https://github.com/neutraltone/settee/commit/3b8b3cdef8a92941708c7fcc54d0c6b24c12bbfb))

## [0.4.0](https://github.com/neutraltone/settee/compare/v0.3.0...v0.4.0) (2026-11-02)


### Features

* **#20:** search remembers your recent searches, and OK opens the keyboard on Fire TV ([98c9259](https://github.com/neutraltone/settee/commit/98c9259035e5e2cc829a8995ef34119b39295e5d))
* **#20:** search remembers your recent searches, and OK opens the keyboard on Fire TV ([be91fa4](https://github.com/neutraltone/settee/commit/be91fa4fa1170f489034b7961464e4b8a207cad7)), closes [#20](https://github.com/neutraltone/settee/issues/20)


### Bug fixes

* **#106:** a series rule books each airing once, and a provider you add always gets its guide ([7e2bc2e](https://github.com/neutraltone/settee/commit/7e2bc2e151f8dc528ed5d28e3ca22ac803ecbba7))
* **#106:** a series rule books each airing once, and a provider you add always gets its guide ([443fa53](https://github.com/neutraltone/settee/commit/443fa53e475a0e4a56e3656d14401d315b1fa29d))
* **#106:** Movies and Shows don't download again right after a refresh, and providers added from your phone get their guide ([b4a86db](https://github.com/neutraltone/settee/commit/b4a86dbee781d5bb98e4e17a4b419364c93237de))
* **#106:** Movies and Shows don't download again right after a refresh, and providers added from your phone get their guide ([c1bd1f8](https://github.com/neutraltone/settee/commit/c1bd1f836012adc3a6d83ba5727172101339847d))
* **#107:** playback errors name the video format your device can't play ([0d1e54b](https://github.com/neutraltone/settee/commit/0d1e54b3ce6d74020220352b1976b6f873efbfda))
* **#107:** playback errors name the video format your device can't play ([c10c58f](https://github.com/neutraltone/settee/commit/c10c58f7e50f84f55005878a35ebd2ac5f788136))
* **#108:** the seek bar shows where you are, and reaching it no longer moves the film ([61b2b06](https://github.com/neutraltone/settee/commit/61b2b062778476cad6261b24c1cb7d7ecdc12a01))
* **#108:** the seek bar shows where you are, and reaching it no longer moves the film ([62a2d73](https://github.com/neutraltone/settee/commit/62a2d738b0914bdd73bf34c83015e28e35f91b08))
* **#110:** live previews stay silent when they're meant to be muted ([25a7a82](https://github.com/neutraltone/settee/commit/25a7a82e86a02fca064fe98ef2b5c919f9b1fa59))
* **#110:** live previews stay silent when they're meant to be muted ([bf81c53](https://github.com/neutraltone/settee/commit/bf81c53d6758d6c7c6b17888cfd466f763cfadc2))
* **#111:** Recently Released Movies fills in without opening a movie, and only shows the last 12 months ([#122](https://github.com/neutraltone/settee/issues/122)) ([c0df6f6](https://github.com/neutraltone/settee/commit/c0df6f608181116c5b2c413be77797e036e7983c))

## [0.3.0](https://github.com/neutraltone/settee/compare/v0.2.0...v0.3.0) (2026-10-30)


### ⚠ BREAKING CHANGES

* **#15:** a long press of Select or the Menu button opens options: mark watched or unwatched, remove from Continue Watching, add or remove a Favourite (add your providers again)
* **#12:** a show you'd finished comes back to Continue Watching when a new episode arrives (add your providers again)
* **#65:** subtitles stay on, in the language you picked, from one title to the next (add your providers again)
* **#59:** Home shows recently released and recently added movies and shows (add your providers again)
* **#13:** each Profile keeps its own Favourites (add your providers and Favourites again)

### Features

* **#10:** play the right episode from the series page ([264f111](https://github.com/neutraltone/settee/commit/264f111c2b7189ed63e4e1902a4daa55b6b63229))
* **#10:** play the right episode from the series page ([7ad7970](https://github.com/neutraltone/settee/commit/7ad797033b56e8713d7ab19198405c2a6c4e3cb5))
* **#11:** Continue Watching shows each series once, moving on to the next episode when you finish one ([ce965e8](https://github.com/neutraltone/settee/commit/ce965e882bee3ca1bf453c33b85ce2e91c2fb2d9))
* **#11:** Continue Watching shows each series once, moving on to the next episode when you finish one ([fa0ba9d](https://github.com/neutraltone/settee/commit/fa0ba9d0cddfd734e5fe58456f22b8e7e9042014))
* **#12:** a show you'd finished comes back to Continue Watching when a new episode arrives (add your providers again) ([fd6a996](https://github.com/neutraltone/settee/commit/fd6a99657aec6fe645f218f3b0e543b1c3a9ab7f))
* **#13:** each Profile keeps its own Favourites (add your providers and Favourites again) ([942a068](https://github.com/neutraltone/settee/commit/942a0680ee242d7d232bb0fa29789622d26bf699))
* **#15:** a long press of Select or the Menu button opens options: mark watched or unwatched, remove from Continue Watching, add or remove a Favourite (add your providers again) ([c54f2d5](https://github.com/neutraltone/settee/commit/c54f2d5668e616fb47e673f9cb0b829f63c3d254))
* **#16:** the app opens on a Home tab with Continue Watching and your favourite shows, movies and channels ([dd816b3](https://github.com/neutraltone/settee/commit/dd816b301e6a5655ef3a6f64636bbe9b10f893e3))
* **#16:** the app opens on a Home tab with Continue Watching and your favourite shows, movies and channels ([4dd61fe](https://github.com/neutraltone/settee/commit/4dd61fefc1aa6be57523f1780840fe489684d21b))
* **#17:** asks "Who's watching?" when the app starts and there's more than one profile ([d750bdd](https://github.com/neutraltone/settee/commit/d750bdd01c5d6f85476c696b594e00e9b370d60b))
* **#17:** asks "Who's watching?" when the app starts and there's more than one profile ([0d307da](https://github.com/neutraltone/settee/commit/0d307dacebde150a4ca2df7ea9b0df90406d643a))
* **#18:** Home opens on the live channel you were last watching, playing silently ([7cbc80f](https://github.com/neutraltone/settee/commit/7cbc80f39ce32a6d24e7be7a77b6a21b76761b7d))
* **#18:** Home opens on the live channel you were last watching, playing silently ([4a44bad](https://github.com/neutraltone/settee/commit/4a44bad5ad3cb0506329a4d8283bf4188d3838a3))
* **#19:** type with the device keyboard, so the Fire TV mic can dictate searches and profile names ([6aab078](https://github.com/neutraltone/settee/commit/6aab078dda7267e125d545c54cc57072b7bff4f3))
* **#19:** type with the device keyboard, so the Fire TV mic can dictate searches and profile names ([4b1645d](https://github.com/neutraltone/settee/commit/4b1645d0b751999fa52f399b78187e664b29939f))
* **#42:** see what's new after Settee updates ([040b7c3](https://github.com/neutraltone/settee/commit/040b7c3ac86e878aa0c47796f4d67bbed141a753))
* **#42:** see what's new after Settee updates ([545d62e](https://github.com/neutraltone/settee/commit/545d62e787d39a8c5a5a7af870d39901f584b4e6))
* **#51:** move episode watch badges onto the thumbnail with a text label ([27a9f90](https://github.com/neutraltone/settee/commit/27a9f90bd185fffbd35ebc7b857361adc5bd194e))
* **#51:** move episode watch badges onto the thumbnail with a text label ([5000c42](https://github.com/neutraltone/settee/commit/5000c4292d2ce6d763dada139c764ce078f40191))
* **#59:** Home shows recently released and recently added movies and shows (add your providers again) ([c7ddf36](https://github.com/neutraltone/settee/commit/c7ddf36841f1f762bd9e8ecd2e6901b5a9a6537b))
* **#63:** Home returns to the card you picked when you come back to it ([bb7f7fc](https://github.com/neutraltone/settee/commit/bb7f7fc2667f642eaf864101183387bc844d7f0c))
* **#63:** Home returns to the card you picked when you come back to it ([a8b6749](https://github.com/neutraltone/settee/commit/a8b6749217444db806b7caab996e74778a180ec2))
* **#65:** subtitles stay on, in the language you picked, from one title to the next (add your providers again) ([03792e0](https://github.com/neutraltone/settee/commit/03792e099e3ef4e83d2e0e44c262a8a63a8d840c))
* **#67:** the players have a Back button, and Back takes you to the guide or the title's page ([ab01310](https://github.com/neutraltone/settee/commit/ab01310cd42272b992a2cd8abe2fc1eac1ef1673))
* **#67:** the players have a Back button, and Back takes you to the guide or the title's page ([5bbfba8](https://github.com/neutraltone/settee/commit/5bbfba8dcb8c9e60c663e1b5cdc8d3f3f02db444))
* **#68:** the remote's play/pause, rewind and fast-forward work in the players, and pausing live TV offers to turn on live pause ([47e1a1e](https://github.com/neutraltone/settee/commit/47e1a1e60458a961821e5b64a89f3f04ebe0cdd5))
* **#68:** the remote's play/pause, rewind and fast-forward work in the players, and pausing live TV offers to turn on live pause ([ac119d1](https://github.com/neutraltone/settee/commit/ac119d1bd66f864230d1f2e8fae954fb79a4f5b3))
* **#69:** the next episode plays automatically, with a countdown card to skip the credits and an "Are you still watching?" check ([44fc7a6](https://github.com/neutraltone/settee/commit/44fc7a61370b3f137a4479804a002bbeae013879))
* **#69:** the next episode plays automatically, with a countdown card to skip the credits and an "Are you still watching?" check ([be8d5dc](https://github.com/neutraltone/settee/commit/be8d5dc6cf1340e792543151a26472c0b4c1079e))
* **#9:** show watch progress on series episodes via a Viewer Library ([a362061](https://github.com/neutraltone/settee/commit/a3620612b8565b9ff1ac9ba77fbd0711cafb6a56))
* **#9:** show watch progress on series episodes via a Viewer Library ([516bf6a](https://github.com/neutraltone/settee/commit/516bf6a2fbe0705585fc7b6f02ced1a7dd664c5b))


### Bug fixes

* **#100:** removing a profile clears everything it owned ([7e20542](https://github.com/neutraltone/settee/commit/7e2054227828d6a0c6a131f401ca4eb714083ba7))
* **#100:** removing a profile clears everything it owned ([eb47aa4](https://github.com/neutraltone/settee/commit/eb47aa4ffc3b7cd958d13b6d2021b4f7af5cb85a))
* **#104:** search and Home respect the parental lock, and a favourite channel shows once ([7c1c4b1](https://github.com/neutraltone/settee/commit/7c1c4b168e06a930d78b4f2f792748eb680a8f32))
* **#104:** search and Home respect the parental lock, and a favourite channel shows once ([7fa03c1](https://github.com/neutraltone/settee/commit/7fa03c11c7032b52b4eabb0d7026748cbcc751d6))
* **#11:** finished series no longer push others off Continue Watching, and the player names the episode ([5c044e1](https://github.com/neutraltone/settee/commit/5c044e171d253b40488ed63c8fcafb0c120e4f80))
* **#11:** Up Next cards show no progress bar until you start the episode ([114c1a0](https://github.com/neutraltone/settee/commit/114c1a0355cc9bbed1d98fbf4b6cafb8eca07742))
* **#16:** Home leaves the highlight on the side menu when you pick it there, and leaves out content types you've switched off ([3a529da](https://github.com/neutraltone/settee/commit/3a529dab13f347e6528927dda278269999a1ff83))
* **#17:** a recording starting no longer skips Who's watching?, and choosing after a restore opens Home ([c39e4a6](https://github.com/neutraltone/settee/commit/c39e4a6e2702d71ced8a7e919dabea109853fc5d))
* **#18:** marking a title or watching on another device no longer hides the Live Preview ([93cd965](https://github.com/neutraltone/settee/commit/93cd965c99008cfbd7a4dc78cd0dca7ce631561d))
* **#64:** with nothing on Home, the highlight starts on the side menu so the remote can move straight to another tab ([dde28fa](https://github.com/neutraltone/settee/commit/dde28fa371bb757f648c45ad03db5d0226e695cc))
* **#64:** with nothing on Home, the highlight starts on the side menu so the remote can move straight to another tab ([7182c3e](https://github.com/neutraltone/settee/commit/7182c3e57c3cf322d5b52a63299e706dacb600a9))
* **#67:** tapping Back twice leaves the player once, and Back from a film always finds its page ([0d3a8f9](https://github.com/neutraltone/settee/commit/0d3a8f90037b5bf8fd6b0f016526e044c200730e))
* **#98:** address review of playback through PlaybackSources ([97f71f8](https://github.com/neutraltone/settee/commit/97f71f870b27e60ac270e90830c3ebe1273f7fde))
* **#98:** Stalker channels play in the Live Preview, the guide and recordings, and movies use the provider's user agent ([64b7e10](https://github.com/neutraltone/settee/commit/64b7e10d250515ddfbc5807397d0efdcb1721ae8))
* **#98:** Stalker channels play in the Live Preview, the guide and recordings, and movies use the provider's user agent ([b45cd9f](https://github.com/neutraltone/settee/commit/b45cd9fb699b9b6dcb6e43af33e9c1901abefc66))

## [0.2.0](https://github.com/neutraltone/settee/compare/v0.1.0...v0.2.0) (2026-10-28)


### docs

* **#8:** add Fire TV install steps and how to cut the first release ([4dfb10e](https://github.com/neutraltone/settee/commit/4dfb10e30501d399abf92de469eaf33fa1268b5c))

## 0.1.0

- **Settee.** The first release under the new name, as its own app: it installs alongside OpenTV
  rather than over it, so add your providers again once.
