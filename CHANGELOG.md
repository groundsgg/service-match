# Changelog

## [1.9.0](https://github.com/groundsgg/service-match/compare/v1.8.1...v1.9.0) (2026-10-19)


### Features

* **match:** PREPARING state, round-ready signal and map selection ([#90](https://github.com/groundsgg/service-match/issues/90)) ([58f2528](https://github.com/groundsgg/service-match/commit/58f2528634506b7fb121c70a7142447ba8a1532e))

## [1.8.1](https://github.com/groundsgg/service-match/compare/v1.8.0...v1.8.1) (2026-10-18)


### Bug Fixes

* **valkey:** give the matchmaker its own Valkey client and answer 503 when the API's runs out ([#88](https://github.com/groundsgg/service-match/issues/88)) ([c0a3a9b](https://github.com/groundsgg/service-match/commit/c0a3a9bae662ea7c757d54dbbd2f121579ab8e79))

## [1.8.0](https://github.com/groundsgg/service-match/compare/v1.7.0...v1.8.0) (2026-10-18)


### Features

* report match results asynchronously through JetStream ([#86](https://github.com/groundsgg/service-match/issues/86)) ([2f7327f](https://github.com/groundsgg/service-match/commit/2f7327ffa73e3f2a2c9ba5a07961da69e91cc25e))

## [1.7.0](https://github.com/groundsgg/service-match/compare/v1.6.0...v1.7.0) (2026-10-17)


### Features

* **db:** run Flyway on a direct migrations datasource ([#84](https://github.com/groundsgg/service-match/issues/84)) ([09cee0b](https://github.com/groundsgg/service-match/commit/09cee0b3574399ecf9c2e08fd77b2c781a555f2c))

## [1.6.0](https://github.com/groundsgg/service-match/compare/v1.5.0...v1.6.0) (2026-10-17)


### Features

* **api:** look up the match a player is playing, for a rejoin from any proxy ([#82](https://github.com/groundsgg/service-match/issues/82)) ([ec9110f](https://github.com/groundsgg/service-match/commit/ec9110f207756d4be89fbe2a646f3aa561477500))
* keep the 8-character match short id unique ([#83](https://github.com/groundsgg/service-match/issues/83)) ([9c437fe](https://github.com/groundsgg/service-match/commit/9c437fe90d562ac48f896ab95af5d55b42de1017))


### Bug Fixes

* **build:** resolve dependencies through the runner mirror in Docker builds ([#75](https://github.com/groundsgg/service-match/issues/75)) ([852900c](https://github.com/groundsgg/service-match/commit/852900c5a490cffe6c57def8020f73358d041431))

## [1.5.0](https://github.com/groundsgg/service-match/compare/v1.4.0...v1.5.0) (2026-10-17)


### Features

* **timeline:** match timeline metrics, push headers and trace ([#73](https://github.com/groundsgg/service-match/issues/73)) ([c95d60e](https://github.com/groundsgg/service-match/commit/c95d60e03e723055d4f86fb9b642d13d1b18c096))

## [1.4.0](https://github.com/groundsgg/service-match/compare/v1.3.0...v1.4.0) (2026-10-16)


### Features

* **modes:** lease-based dynamic game modes ([#71](https://github.com/groundsgg/service-match/issues/71)) ([c93dec9](https://github.com/groundsgg/service-match/commit/c93dec99e144deba8440a031d6d0ee60b5afd9f8))

## [1.3.0](https://github.com/groundsgg/service-match/compare/v1.2.0...v1.3.0) (2026-10-16)


### Features

* **modes:** let a free-for-all start under-filled after a fill time ([#69](https://github.com/groundsgg/service-match/issues/69)) ([096b045](https://github.com/groundsgg/service-match/commit/096b0451eb372e8bada2e8ffc827ec1cca27e7a5))

## [1.2.0](https://github.com/groundsgg/service-match/compare/v1.1.1...v1.2.0) (2026-10-11)


### Features

* **metrics:** record per-player queue wait time ([#67](https://github.com/groundsgg/service-match/issues/67)) ([a5819df](https://github.com/groundsgg/service-match/commit/a5819dfcc0f9a923636ac18bff805b8cf2f83533))

## [1.1.1](https://github.com/groundsgg/service-match/compare/v1.1.0...v1.1.1) (2026-09-28)


### Bug Fixes

* **matcher:** read an unknown mode through from valkey ([#65](https://github.com/groundsgg/service-match/issues/65)) ([557ea3d](https://github.com/groundsgg/service-match/commit/557ea3d532a536d6bcfa6f34ea29decad1951dd5))

## [1.1.0](https://github.com/groundsgg/service-match/compare/v1.0.0...v1.1.0) (2026-08-10)


### Features

* **match:** let a mode name the fleet that serves it ([#43](https://github.com/groundsgg/service-match/issues/43)) ([8a6b76f](https://github.com/groundsgg/service-match/commit/8a6b76f996496c4474f4d95b3600aebe8404bada))

## [1.0.0](https://github.com/groundsgg/service-match/compare/v0.9.0...v1.0.0) (2026-08-07)


### ⚠ BREAKING CHANGES

* **api:** drop the MatchService gRPC server and the MatchHost fallback ([#38](https://github.com/groundsgg/service-match/issues/38))

### Features

* **api:** drop the MatchService gRPC server and the MatchHost fallback ([#38](https://github.com/groundsgg/service-match/issues/38)) ([5036f48](https://github.com/groundsgg/service-match/commit/5036f481efa1f608a05111099130495ab9f3e99e))
* **leaderboard:** post rated results over HTTP instead of gRPC ([#40](https://github.com/groundsgg/service-match/issues/40)) ([34a1212](https://github.com/groundsgg/service-match/commit/34a121229b2fec4b12aca4daeee374ec38ed5ff6))

## [0.9.0](https://github.com/groundsgg/service-match/compare/v0.8.0...v0.9.0) (2026-08-04)


### Features

* **allocator:** push matches over NATS instead of dialling the pod ([#36](https://github.com/groundsgg/service-match/issues/36)) ([4064868](https://github.com/groundsgg/service-match/commit/40648683c6e17346ed009d6b4f4dee21fa258d60))

## [0.8.0](https://github.com/groundsgg/service-match/compare/v0.7.0...v0.8.0) (2026-08-04)


### Features

* **api:** serve the matchmaker over REST, push matches over NATS ([#34](https://github.com/groundsgg/service-match/issues/34)) ([492676e](https://github.com/groundsgg/service-match/commit/492676eaa1aff0dc3353c3e3e8aa491dc9c744ed))

## [0.7.0](https://github.com/groundsgg/service-match/compare/v0.6.0...v0.7.0) (2026-08-04)


### Features

* **metrics:** expose the Agroal connection-pool gauges ([#32](https://github.com/groundsgg/service-match/issues/32)) ([3fe1fd7](https://github.com/groundsgg/service-match/commit/3fe1fd710bade938b1d478249bc1c18eb9dbfded))

## [0.6.0](https://github.com/groundsgg/service-match/compare/v0.5.0...v0.6.0) (2026-07-31)


### Features

* **queue:** let players queue when the rating store is unreachable ([#29](https://github.com/groundsgg/service-match/issues/29)) ([b24bb4d](https://github.com/groundsgg/service-match/commit/b24bb4d0e89ad93a3b48ba06396efe44ec1d262a))

## [0.5.0](https://github.com/groundsgg/service-match/compare/v0.4.0...v0.5.0) (2026-07-30)


### Features

* **match:** make duel a ranked mode ([#27](https://github.com/groundsgg/service-match/issues/27)) ([53a370e](https://github.com/groundsgg/service-match/commit/53a370ed057418e3e3419ce4c9f3f289d75758f7))

## [0.4.0](https://github.com/groundsgg/service-match/compare/v0.3.2...v0.4.0) (2026-07-30)


### Features

* **metrics:** expose matchmaking metrics on /q/metrics ([#25](https://github.com/groundsgg/service-match/issues/25)) ([8138d03](https://github.com/groundsgg/service-match/commit/8138d032a4b9a62b888e2b181f2722168fbb4676))

## [0.3.2](https://github.com/groundsgg/service-match/compare/v0.3.1...v0.3.2) (2026-07-14)


### Bug Fixes

* **leaderboard:** shift the board score so a fresh player is not zero ([#18](https://github.com/groundsgg/service-match/issues/18)) ([19604ae](https://github.com/groundsgg/service-match/commit/19604ae46d591222cc25a60e7cec572e6c5583bd))

## [0.3.1](https://github.com/groundsgg/service-match/compare/v0.3.0...v0.3.1) (2026-07-14)


### Bug Fixes

* **api:** UpsertQueue must be @Blocking now that it writes to Valkey ([#16](https://github.com/groundsgg/service-match/issues/16)) ([3ddd649](https://github.com/groundsgg/service-match/commit/3ddd64961ce09aea4a363bbc4f3348207c4b3aff))

## [0.3.0](https://github.com/groundsgg/service-match/compare/v0.2.2...v0.3.0) (2026-07-14)


### Features

* **match:** persist mode configs and write ranked results to the leaderboard ([#14](https://github.com/groundsgg/service-match/issues/14)) ([091abf9](https://github.com/groundsgg/service-match/commit/091abf9b1f64903607f78e86f036f77f3ba84b26))

## [0.2.2](https://github.com/groundsgg/service-match/compare/v0.2.1...v0.2.2) (2026-07-13)


### Bug Fixes

* **allocator:** drain the stream RESP3 gives us, and find the pod's IP ([#12](https://github.com/groundsgg/service-match/issues/12)) ([bfd2982](https://github.com/groundsgg/service-match/commit/bfd2982cd3e79c8460667f174d0d7e3a0a3440b1))

## [0.2.1](https://github.com/groundsgg/service-match/compare/v0.2.0...v0.2.1) (2026-07-13)


### Bug Fixes

* **grpc:** the RPCs that touch Valkey and Postgres must be @Blocking ([#10](https://github.com/groundsgg/service-match/issues/10)) ([3f77dd7](https://github.com/groundsgg/service-match/commit/3f77dd71a5269075ebc3052b3e54360d3147115d))

## [0.2.0](https://github.com/groundsgg/service-match/compare/v0.1.0...v0.2.0) (2026-07-13)


### Features

* the matchmaker end to end — queue, allocation, results, and the multi-match runtime ([#8](https://github.com/groundsgg/service-match/issues/8)) ([7deb73e](https://github.com/groundsgg/service-match/commit/7deb73e97273f4e3887e36c5436580b93f86375a))

## 0.1.0 (2026-07-12)


### Features

* scaffold service-match with golden-vector-pinned ratings ([37ef97b](https://github.com/groundsgg/service-match/commit/37ef97be6795eb1ad207ff353f7bf1bf1b59eb3b))
