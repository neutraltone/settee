# Changelog

Settee is based on OpenTV 0.11.8; everything before this is in [OpenTV's changelog](https://github.com/opentvproject/opentv/blob/v0.11.8/CHANGELOG.md).

## [0.7.0](https://github.com/neutraltone/settee/compare/v0.6.0...v0.7.0) (2026-11-14)


### Features

* **#267:** Locked categories has Find a category, and no longer says there are none while it's reading them ([#317](https://github.com/neutraltone/settee/issues/317)) ([c9e59f9](https://github.com/neutraltone/settee/commit/c9e59f957294383d2567c4f8b51c58ba4d603e0c))


### Bug fixes

* **#253:** guide listings pick up the provider's corrections when the guide refreshes ([#278](https://github.com/neutraltone/settee/issues/278)) ([ab359ff](https://github.com/neutraltone/settee/commit/ab359ff33e7bf5f50277118081984f712753e17d))
* **#255:** a channel that can't play says so in seconds, not a minute ([#280](https://github.com/neutraltone/settee/issues/280)) ([9139bda](https://github.com/neutraltone/settee/commit/9139bda1011f489e1cd8aabe7dc2e193958f8231))
* **#256:** a provider with a very large channel line-up loads without running out of memory ([#285](https://github.com/neutraltone/settee/issues/285)) ([a980784](https://github.com/neutraltone/settee/commit/a980784721a25d89293593143d776b4f961ad07b))
* **#259:** NAS recordings start sooner and stall less on a slow network ([#313](https://github.com/neutraltone/settee/issues/313)) ([1ffad16](https://github.com/neutraltone/settee/commit/1ffad16bfa68158d22c7313f781a69161c21079a))
* **#261:** a portal channel list too big for the TV is read a channel at a time, and says so if it still runs out of memory ([#284](https://github.com/neutraltone/settee/issues/284)) ([5270b46](https://github.com/neutraltone/settee/commit/5270b46e87ce3d9f7e57f46120db324ea1b1cff1))
* **#262:** films and episodes in an M3U playlist no longer show up as live channels ([#286](https://github.com/neutraltone/settee/issues/286)) ([cd9e275](https://github.com/neutraltone/settee/commit/cd9e2752ed2edc8d94edcc3e97eef36b1474094f))
* **#264:** after holding Down through Movies or Shows, the covers where you stop load straight away ([#318](https://github.com/neutraltone/settee/issues/318)) ([c73d133](https://github.com/neutraltone/settee/commit/c73d133d20f590907f02fb3ef3a4c37962e00082))
* **#268:** live previews no longer stutter every five minutes ([#279](https://github.com/neutraltone/settee/issues/279)) ([1c311f4](https://github.com/neutraltone/settee/commit/1c311f4d5f16cd15b94fd90a01744c71d1de5abb))
* **#325:** a playlist that briefly comes back with only films and episodes no longer deletes your channels ([#329](https://github.com/neutraltone/settee/issues/329)) ([c162683](https://github.com/neutraltone/settee/commit/c1626837cbe307b11dbd0e657c78f4a0304605d8))
* **#326:** a show you've opened before draws its page at once, even when its provider is slow ([#330](https://github.com/neutraltone/settee/issues/330)) ([3ab9489](https://github.com/neutraltone/settee/commit/3ab948966163f2ce8d93fd191cf89e0f52ff9099))
* **#328:** long programmes show as on now, Search keeps its results after a title, and Parental lock's count matches its switches ([#331](https://github.com/neutraltone/settee/issues/331)) ([122abeb](https://github.com/neutraltone/settee/commit/122abebf0c857bc6bb884793af29bdb877cc895a))
* **#333:** series links, reminders and your channel edits survive a channel missing from one refresh ([#382](https://github.com/neutraltone/settee/issues/382)) ([30079aa](https://github.com/neutraltone/settee/commit/30079aa84828bb5557b6af5e55b198063fae5b64))
* **#334:** a database Settee can't open says how to get out and offers the update, instead of the app stopping on every launch ([#381](https://github.com/neutraltone/settee/issues/381)) ([727a6e5](https://github.com/neutraltone/settee/commit/727a6e594987a78ce33e2fc3397dfa32d762f891))
* **#372:** a problem while the app starts no longer stops it on every launch ([#383](https://github.com/neutraltone/settee/issues/383)) ([29f261e](https://github.com/neutraltone/settee/commit/29f261e0454e02b85cfe279e35ce209248acb6bb))
* **#384:** back from a film or show's page, the highlight is on the title you opened, not the main nav ([#385](https://github.com/neutraltone/settee/issues/385)) ([3b4ea9b](https://github.com/neutraltone/settee/commit/3b4ea9b727edd67b0e1c34fdd36d0f6923391a8c))


### Performance

* **#192:** back to Home from Settings, Profiles or Search draws its rows straight away ([#312](https://github.com/neutraltone/settee/issues/312)) ([6d10200](https://github.com/neutraltone/settee/commit/6d102009a85ccaf5a033c990bc03674790bda559))
* **#192:** back to Home from Settings, Search or a title's page keeps your place instead of redrawing Home ([#322](https://github.com/neutraltone/settee/issues/322)) ([e04ea88](https://github.com/neutraltone/settee/commit/e04ea8838c7384139012da892ae5fd33011787c3))
* **#254:** moving around the guide no longer judders, as each row builds only the programmes near the screen ([#287](https://github.com/neutraltone/settee/issues/287)) ([d6482cf](https://github.com/neutraltone/settee/commit/d6482cf56325fbdc20786dae8834311f0eec8c35))
* **#257:** the guide stays smooth while its listings are updating ([#289](https://github.com/neutraltone/settee/issues/289)) ([0d8f6e9](https://github.com/neutraltone/settee/commit/0d8f6e9b9d31cbe67c09cbea38023f59ee652b09))
* **#258:** the app and Search open without reading the whole film and show library, which waits until Movies or Shows opens ([#308](https://github.com/neutraltone/settee/issues/308)) ([785aa1c](https://github.com/neutraltone/settee/commit/785aa1c6694b366905524aaff9b99c9d2ac160a4))
* **#260:** a show's episodes appear sooner when you open it ([#311](https://github.com/neutraltone/settee/issues/311)) ([ae294b5](https://github.com/neutraltone/settee/commit/ae294b53a99536c81323262b4c85ce26fe9cc87d))
* **#263:** changing a provider's stream format, or reordering channels from your phone, saves in a moment ([#314](https://github.com/neutraltone/settee/issues/314)) ([8d097f6](https://github.com/neutraltone/settee/commit/8d097f6dd416110874d80316e2dfa5535671cf50))
* **#265:** Home and the channel list look up what's on now faster ([#290](https://github.com/neutraltone/settee/issues/290)) ([0ae0713](https://github.com/neutraltone/settee/commit/0ae0713d933074526e366c8568edcc0539f36a59))
* **#266:** the guide uses less memory on a big provider ([#291](https://github.com/neutraltone/settee/issues/291)) ([e562b02](https://github.com/neutraltone/settee/commit/e562b02ea2efba9819acc1ca4e6a7c5ddd2458ad))
* **#267:** moving down past Parental lock in Settings no longer stalls on a big provider ([#316](https://github.com/neutraltone/settee/issues/316)) ([79d72dc](https://github.com/neutraltone/settee/commit/79d72dc0b47fade56a5ef923dd047365ec418026))
* **#269:** refreshing a provider takes less time with a large catalogue ([#323](https://github.com/neutraltone/settee/issues/323)) ([78f0dfd](https://github.com/neutraltone/settee/commit/78f0dfdf35e749f0d69ec0ed43060b9e07ad7ad9))
* **#270:** Home stays smooth while film and show details are looked up ([#288](https://github.com/neutraltone/settee/issues/288)) ([df1cb50](https://github.com/neutraltone/settee/commit/df1cb5077b4a46f91d9b15dda85243334c0632c9))
* **#271:** Home's Favourite channels row loads faster when a Favourite has several Qualities ([#309](https://github.com/neutraltone/settee/issues/309)) ([03698f1](https://github.com/neutraltone/settee/commit/03698f1e62f9601fa7f712ec4ecac2e409d7f4f5))
* **#272:** Live and the player open faster, reading the channel list once instead of twice ([#315](https://github.com/neutraltone/settee/issues/315)) ([5d2d46e](https://github.com/neutraltone/settee/commit/5d2d46eb8e11201236d1be989417200c6161618f))
* **#273:** refreshing a large channel list uses less memory while something plays ([#282](https://github.com/neutraltone/settee/issues/282)) ([a144543](https://github.com/neutraltone/settee/commit/a1445431ba8c9a01609caf36aff4909d81ca29d8))
* **#274:** looking up film and show details in the background reads far less from the database ([#310](https://github.com/neutraltone/settee/issues/310)) ([be6b7ac](https://github.com/neutraltone/settee/commit/be6b7ac12007a56e85df58f036d151e052ad6c58))
* **#275:** a recording or reminder that wakes the app no longer sets off a guide download and NAS sync ([#298](https://github.com/neutraltone/settee/issues/298)) ([46b1927](https://github.com/neutraltone/settee/commit/46b19279a7180cebf162c5281c0fbfd4286e4248))
* **#276:** starting the app no longer re-reads the whole film and show library ([#292](https://github.com/neutraltone/settee/issues/292)) ([4525471](https://github.com/neutraltone/settee/commit/452547169f91349f6afd4dad9b14fb678d94797a))
* **#277:** watching a NAS recording while it records no longer stops early, and Home, Live TV and For you do less work ([#324](https://github.com/neutraltone/settee/issues/324)) ([fb47543](https://github.com/neutraltone/settee/commit/fb47543b4bb5fb66e97f42d6d88e09956a2a4263))
* **#327:** Live TV stops updating its guide in the background while Settings or another screen covers it ([#332](https://github.com/neutraltone/settee/issues/332)) ([1c0b1e1](https://github.com/neutraltone/settee/commit/1c0b1e166837ca7ed0f3f35a35567eeb23f18f7d))

## [0.6.0](https://github.com/neutraltone/settee/compare/v0.5.0...v0.6.0) (2026-11-13)


### Features

* **#159:** the rail opens with labels and slides smoothly, Search sits at the top, and Right takes you back to the row you left ([#202](https://github.com/neutraltone/settee/issues/202)) ([4ae11db](https://github.com/neutraltone/settee/commit/4ae11dbf017160908e8f0a7005f3df33f0cd7409))
* **#160:** Home has rows for Recently added and Favourite shows and movies, shows what's on under your favourite channels, and remembers where you were in each row ([#210](https://github.com/neutraltone/settee/issues/210)) ([6588d46](https://github.com/neutraltone/settee/commit/6588d46a02a5b6f5564d37740afc7bdc3435b1d7))
* **#161:** Movies and Shows open on their first cover, with big artwork, a categories sidebar you can search, and a row for every genre ([#217](https://github.com/neutraltone/settee/issues/217)) ([dd16161](https://github.com/neutraltone/settee/commit/dd16161c8f4cd69b0c0538112778d3b919957aaf))
* **#162:** film and show pages open on a big hero, and a show's seasons are pills you move along ([#215](https://github.com/neutraltone/settee/issues/215)) ([727a770](https://github.com/neutraltone/settee/commit/727a770ffb3fa13120e3e1985cb031b60416bdd6))
* **#163:** the player's controls start on a seek bar you scrub with Left and Right, with Subtitles and Audio beside it and less-used actions as pills below ([#216](https://github.com/neutraltone/settee/issues/216)) ([2374d4e](https://github.com/neutraltone/settee/commit/2374d4e27d08f1daa77428b377543be33d57299c))
* **#164:** Live TV's guide is ruled and square, with categories in a sidebar and programme details in a side panel, and the List layout is gone ([#218](https://github.com/neutraltone/settee/issues/218)) ([02704f5](https://github.com/neutraltone/settee/commit/02704f5d1171aec4e02baed69ea50d31b7db6b7a))
* **#165:** changing channel shows what's on, how far through it is and what's next, and the channel list shows what's on each channel ([#221](https://github.com/neutraltone/settee/issues/221)) ([ec2027a](https://github.com/neutraltone/settee/commit/ec2027a782ad0fed9d59938b53104434ff21f4ee))
* **#166:** every error says what happened and what to do, with its code, status and format in a quiet line underneath ([#223](https://github.com/neutraltone/settee/issues/223)) ([4b4fb1a](https://github.com/neutraltone/settee/commit/4b4fb1aa3c08ca144eda1d228b7e16aa3df36e42))
* **#167:** adding a provider starts on one page of choices, with setting up from your phone first on a TV and a form of its own for each kind ([#222](https://github.com/neutraltone/settee/issues/222)) ([4f97304](https://github.com/neutraltone/settee/commit/4f9730498362b6a724a3bab613bcf0211d247836))
* **#168:** every Settings section works beside its list, Profiles has the new look, and removing anything asks first ([#237](https://github.com/neutraltone/settee/issues/237)) ([30798d6](https://github.com/neutraltone/settee/commit/30798d6cb9295432d977bea5a2e7dc6ac03019db))
* **#168:** Settings shows each section's options beside it ([#228](https://github.com/neutraltone/settee/issues/228)) ([b090ad3](https://github.com/neutraltone/settee/commit/b090ad3784621c81c7d40bf27a11f1beb71af013))
* **#169:** Recordings is one list under Coming up, Recorded and Series links, and deleting a recording asks first ([#230](https://github.com/neutraltone/settee/issues/230)) ([9d0d28f](https://github.com/neutraltone/settee/commit/9d0d28f639959594ccb9f6b62bf880668f2d31e9))
* **#170:** every question opens down the right, and Settee waits with its own aerial TV ([#240](https://github.com/neutraltone/settee/issues/240)) ([5ea40fa](https://github.com/neutraltone/settee/commit/5ea40fa49fa5e3c7272a805f57db0ac1b12a23c8))
* **#181:** a film or show is listed once, with a Quality pill to choose between its versions ([#246](https://github.com/neutraltone/settee/issues/246)) ([192d716](https://github.com/neutraltone/settee/commit/192d71697e5b9ff914c4f55ebdf1054a2fd889a7))
* **#219:** a film's cast and crew, and a show's cast, open a page of everything they're in ([#226](https://github.com/neutraltone/settee/issues/226)) ([9c69b81](https://github.com/neutraltone/settee/commit/9c69b813a73bf975d816e480bdaaa8ac71886e4d))
* **#224:** adding your first provider shows Setup, one bar for your channels, guide, movies and shows, then You're all set ([#232](https://github.com/neutraltone/settee/issues/232)) ([df78cf9](https://github.com/neutraltone/settee/commit/df78cf95fa8f19d2210a5978bb74c63100551c06))
* **#239:** add your TMDB key from your phone, in its own Settings section ([#241](https://github.com/neutraltone/settee/issues/241)) ([a2cfd6e](https://github.com/neutraltone/settee/commit/a2cfd6e575977278fee3cc0be16e6db5497da962))
* **#245:** find subtitles on OpenSubtitles when a film or episode has none, and adjust their timing ([#248](https://github.com/neutraltone/settee/issues/248)) ([f7ec2ee](https://github.com/neutraltone/settee/commit/f7ec2ee804492aa7629211ade5528eeb1ef65e7b))


### Bug fixes

* **#203:** checking for updates says when it couldn't check, instead of saying you're up to date ([#204](https://github.com/neutraltone/settee/issues/204)) ([dfaebc5](https://github.com/neutraltone/settee/commit/dfaebc51e2f364ee7af2179ab309ab8cba52ea07))
* **#211:** adding a big provider no longer freezes on Loading movies ([#213](https://github.com/neutraltone/settee/issues/213)) ([e7cb12b](https://github.com/neutraltone/settee/commit/e7cb12baae65df21d2d1fce78819ba9d0cda9d4e))
* **#224:** messages and settings read as plain sentences, without long dashes ([#235](https://github.com/neutraltone/settee/issues/235)) ([270ca05](https://github.com/neutraltone/settee/commit/270ca05c997dfb55b511e415ce7a5876a88e9a6a))
* **#224:** the free USA and Canada guides load again, and one Settee turns on by itself no longer reports a failure ([#229](https://github.com/neutraltone/settee/issues/229)) ([f2a9be6](https://github.com/neutraltone/settee/commit/f2a9be6b9a9af6cbca0ef3503e4c523cd5e0289c))
* **#233:** recording on a Fire TV Stick no longer asks "Keep recording when you leave?" and sends you to Fire TV Home ([#234](https://github.com/neutraltone/settee/issues/234)) ([5b09f15](https://github.com/neutraltone/settee/commit/5b09f1500a270fe5d1dc74042abed0d86403dfd2))
* **#233:** Settings no longer offers a Fix for background recording that does nothing on a Fire TV Stick ([#236](https://github.com/neutraltone/settee/issues/236)) ([aae5e9e](https://github.com/neutraltone/settee/commit/aae5e9e9d0572393b29e8f74b7a1ce561c501e26))

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
