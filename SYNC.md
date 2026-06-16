✅ Bump org.apache.logging.log4j:log4j-core from 2.25.3 to 2.25.4 (#393): https://github.com/WaterdogPE/WaterdogPE/commit/8131dfdf3b199c84a8e0f7314b29c8a79cba6809
  - Ported upstream Maven property update to this fork's Gradle build by updating `log4j2Version` to 2.25.4. This also keeps `log4j-api` aligned with `log4j-core` because the Gradle build uses one shared version.
  - Fork-specific Gradle migration, Java 21 toolchain, and publishing coordinates were preserved.
  - Test: `sh ./gradlew test` passed; `test` had no sources.
✅ Bump raknet version (#394): https://github.com/WaterdogPE/WaterdogPE/commit/c0238184807cc61e06b1ab8a4c8cef571e120d38
  - Ported upstream Maven `raklib.version` update to this fork's Gradle `raklibVersion`.
  - Preserved fork-specific Gradle build layout and NetEase protocol handling.
  - Test: `sh ./gradlew test` passed; `test` had no sources.
✅ Bump raknet version (#395): https://github.com/WaterdogPE/WaterdogPE/commit/66372092c98d0b2e4b1cefe3d8d40c2b36f4f279
  - Ported upstream Maven `raklib.version` update to this fork's Gradle `raklibVersion`.
  - Preserved fork-specific Gradle build layout and NetEase protocol handling.
  - Test: `sh ./gradlew test` passed; `test` had no sources.
✅ Bump raknet version (#396): https://github.com/WaterdogPE/WaterdogPE/commit/a21da617179f3ae7e183006f1fa0093261f8f81f
  - Ported upstream Maven `raklib.version` update to this fork's Gradle `raklibVersion`.
  - Preserved fork-specific Gradle build layout and NetEase protocol handling.
  - Test: `sh ./gradlew test` passed; `test` had no sources.
✅ Do not NPE on wrong login flow: https://github.com/WaterdogPE/WaterdogPE/commit/c5bf314422e096a10c2c5d5de6370a95f4d02ecf
  - Accepted upstream defensive check in `finishConnection()` so a premature `ClientToServerHandshakePacket` disconnects with `Wrong login flow` instead of dereferencing a missing player.
  - Preserved fork-specific NetEase client detection, codec selection, and handshake processing.
  - Test: `sh ./gradlew test` passed; `test` had no sources.
✅ A couple small improvements: https://github.com/WaterdogPE/WaterdogPE/commit/203e08b7e4985e2664c3d7f095dd7a1c39cb1d20
  - Accepted upstream server error handling, connection-error timeout blocking, throttle map max size, and updated network defaults/comments.
  - Adapted `ProxiedBedrockPeer` constructor wiring while preserving fork-specific NetEase client state, packet codec selection, and initial compression behavior.
  - Test: `sh ./gradlew test` passed; `test` had no sources.
✅ Added 1.26.20 (v975) support (#397): https://github.com/WaterdogPE/WaterdogPE/commit/7b9fce1341200342434a9f48cb9d3c8a39d063a7
  - Added `MINECRAFT_PE_1_26_20` with `Bedrock_v975.CODEC`.
  - Adapted upstream debug drawer entity-id rewrite to this fork's current Protocol API by rewriting `PrimitiveShapesPacket` shape attachments while preserving immutable shape subtype fields.
  - Bumped this fork's Protocol dependency to `1.26.30-R1` per local Protocol fork requirement; v1001 is not enabled until the later #411 sync entry.
  - Test: `sh ./gradlew test` passed; `test` had no sources.
✅ Disable plugin when exceptions occur during enable (#398): https://github.com/WaterdogPE/WaterdogPE/commit/87d7a5f9340e48a474989ff3727f756a735eb6a7
  - Accepted upstream plugin lifecycle fix so enable failures attempt to disable the plugin and clear partial enabled state.
  - Test: `sh ./gradlew test` passed; `test` had no sources.
✅ Send CertificateChain on <1.26.20, populate full TokenPayload otherwise (#400): https://github.com/WaterdogPE/WaterdogPE/commit/1047082407625172c7382fdc6b2c7ffd51a23846
  - Accepted upstream payload selection: certificate chain is preserved for pre-1.26.20 clients or original chain payloads; newer token payloads are rebuilt with the full TokenPayload fields including `mid`.
  - Removed `use_certificate_payload` config compatibility in line with upstream, while preserving fork-specific NetEase validation and chain extraData fields.
  - Test: `sh ./gradlew test` passed; `test` had no sources.
✅ Implement network interface system with registration and booting capabilities (#401): https://github.com/WaterdogPE/WaterdogPE/commit/6e6d5f669fdfedb0953d1eaa6ef5d23a3f8ed553
  - Moved RakNet bind/shutdown behavior into `RakNetInterface` and added `NetworkInterface`, `NetworkStartupException`, and cancellable `NetworkRegisterEvent`.
  - Preserved fork-specific fast codec NetEase codec building and `bedrock.maxDecompressedBytes` setup while exposing boss/worker event loop groups for interfaces.
  - Test: `sh ./gradlew test` passed; `test` had no sources.
✅ Expose rewrite API for plugins (#402): https://github.com/WaterdogPE/WaterdogPE/commit/c0be4bda9a5e895248d152d9d8ff54d0f0270a70
  - Added public `RewriteData#rewriteEntityId(long, LongConsumer)` for plugin-facing entity ID rewrite access.
  - Kept this fork's current `PrimitiveShapesPacket` rewrite adaptation instead of upstream's `DebugDrawerPacket` API name.
  - Test: `sh ./gradlew test` passed; `test` had no sources.
✅ Bump io.netty:netty-transport-native-epoll (#405): https://github.com/WaterdogPE/WaterdogPE/commit/01b9dc424e3474dd013f2dc3b49ac76206e5bf82
  - Ported upstream Maven `netty.version` update to this fork's Gradle `nettyVersion` (`4.1.135.Final`).
  - The Gradle build shares this version for epoll and kqueue, so kqueue is also aligned by this change.
  - Test: `sh ./gradlew test` passed; `test` had no sources.
Bump io.netty:netty-transport-native-kqueue (#404): https://github.com/WaterdogPE/WaterdogPE/commit/681b6f6f3d7a1f8f38b6a87c55da274e651fb1fb
Prevent queueing packets after channel is closed: https://github.com/WaterdogPE/WaterdogPE/commit/07fe2557d6e5d17cfc0e0ea660c6b413f6001d68
Store uniqueId from add entity packets: https://github.com/WaterdogPE/WaterdogPE/commit/1e0502717dbc6d52a7a9e8fec467f61ef9fb7939
Track and reset more states on transfer: https://github.com/WaterdogPE/WaterdogPE/commit/b1d0899ca16a15987a3e2a8bb24fe3d14e2dc02d
Limit the max pending query sessions: https://github.com/WaterdogPE/WaterdogPE/commit/0223c77bf5ef23760baea6d8bb45f6c9ab883966
Add timeout to PlayerLoginEvent so it doesn't hang forever: https://github.com/WaterdogPE/WaterdogPE/commit/c4baee25bce1b8435d3d5be743194abea954657e
Force disable client side generation: https://github.com/WaterdogPE/WaterdogPE/commit/2a38fbc638e99a0e5a243aa6fc5324502dfd92d6
Asynchronously resolve server addresses: https://github.com/WaterdogPE/WaterdogPE/commit/953e899cec47e769acf8990a0053a3621c424fe5
Consistently use this.logger on ProxyServer: https://github.com/WaterdogPE/WaterdogPE/commit/0316e93409a75d7cb7fa518a0befdce48dfcc856
Add support for 1.26.30 (v1001) (#411): https://github.com/WaterdogPE/WaterdogPE/commit/b4332d9482007279d96f1ae5c303a4096a5e55d8
Set the disconnect reason to DISCONNECTED: https://github.com/WaterdogPE/WaterdogPE/commit/9cfd93bc6094e7b72703c0edf96ef8517c435282
Add an event called for incompatible protocol to allow custom messages: https://github.com/WaterdogPE/WaterdogPE/commit/27f929f12512158612054b5439d36447fd794b72
Allow closing packs: https://github.com/WaterdogPE/WaterdogPE/commit/4695b161a9e1f19d4b0771272a11e9b3be386b2b
Add ProxyServer#reloadPackManager: https://github.com/WaterdogPE/WaterdogPE/commit/eaf1a9079d2ab1e78baad4189cf7483fdbb9226b
Improve pack sending (#413): https://github.com/WaterdogPE/WaterdogPE/commit/b3ab8f2e463897e6220720a183a187d75ec01649
