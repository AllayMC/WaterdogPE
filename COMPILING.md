# Compiling from source

## Requirements

To compile WaterdogPE you need JDK 21.

### Recommended libraries and versions

- Project uses awesome Protocol library provided by CloudBurst. Their library should be compatible with WDPE.
- **For advanced users:** If you require using custom build of raknet library, you can change it inside of `build.gradle.kts`
  by updating the `raklibVersion` constant. Make sure your version is compatible with WDPE and Protocol library.

## How to complete this guide

- To get latest WDPE source we recommend cloning our GitHub repository
  using `git clone https://github.com/AllayMC/WaterdogPE.git`.
- cd into `WaterdogPE` folder.
- Compile sources using Gradle. You can use `./gradlew build` on Unix-like systems or `gradlew.bat build` on Windows.
- Once Gradle finishes the build you can find your executable `Waterdog.jar` in `build/libs` folder.
