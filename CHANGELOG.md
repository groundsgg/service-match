# Changelog

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
