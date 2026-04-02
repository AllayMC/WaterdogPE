# WaterdogPE

[![Build Status](https://github.com/AllayMC/WaterdogPE/actions/workflows/gradle.yml/badge.svg)](https://github.com/AllayMC/WaterdogPE/actions/workflows/gradle.yml)
[![Maven Central](https://img.shields.io/maven-central/v/org.allaymc/waterdogpe?label=waterdogpe)](https://central.sonatype.com/artifact/org.allaymc/waterdogpe)

WaterdogPE is a brand new Minecraft: Bedrock Edition proxy software developed by the developers of the old Waterdog Proxy.
This is [AllayMC](https://github.com/AllayMC/Allay)'s fork of WaterdogPE with added support for **NetEase Minecraft Bedrock Edition clients**.

## NetEase Client Support

This fork adds the ability for the proxy to handle connections from NetEase Minecraft clients. To enable NetEase client support, add the following to your `config.yml`:

```yaml
netease_client_support: true
# Optional: only allow NetEase clients to connect
only_allow_netease_client: false
```

### Supported NetEase Protocol Versions

| Protocol Version | Game Version |
|------------------|--------------|
| v630             | 1.20.50      |
| v686             | 1.21.2       |
| v766             | 1.21.50      |

> **Note:** When `netease_client_support` is enabled, all RakNet v8 clients will be treated as NetEase clients.

## Links

- [Repository](https://github.com/AllayMC/WaterdogPE)
- [Issue Tracker](https://github.com/AllayMC/WaterdogPE/issues)

## Compiling

To compile WaterdogPE please visit our [COMPILING.md](COMPILING.md) guide.

## Gradle usage

Stable releases are available from Maven Central without adding any extra repository.
Snapshot builds are published to Sonatype Central's snapshot repository.

```kotlin
repositories {
    maven("https://central.sonatype.com/repository/maven-snapshots/")
}

dependencies {
    compileOnly("org.allaymc:waterdogpe:2.0.4-SNAPSHOT")
}
```
