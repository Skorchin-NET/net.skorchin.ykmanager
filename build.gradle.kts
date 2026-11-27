// Top-level build file

// Build tooling only (nothing here ships in the app): the Android Gradle plugin pulls in
// older copies of these libraries, which GitHub's dependency graph reports against
// settings.gradle.kts. Dependabot can't update a plugin's transitive dependencies, so its
// security-update runs for them kept failing. These constraints raise them to patched
// versions on the build classpath. The app's own BouncyCastle (app/build.gradle.kts) is
// pinned separately and locked in app/gradle.lockfile. Drop an entry once AGP ships a
// version at least this new.
buildscript {
    dependencies {
        constraints {
            classpath("com.google.guava:guava:33.8.0-jre") { because("GHSA fixed in 33.7.2") }
            classpath("org.bouncycastle:bcprov-jdk18on:1.87") { because("GHSAs fixed in 1.85; matches the app") }
            classpath("org.bouncycastle:bcpkix-jdk18on:1.87") { because("GHSA fixed in 1.84; matches the app") }
            classpath("org.bouncycastle:bcutil-jdk18on:1.87") { because("keep BouncyCastle modules aligned") }
            classpath("org.jdom:jdom2:2.0.6.1") { because("XXE fixed in 2.0.6.1") }
            classpath("org.bitbucket.b_c:jose4j:0.9.6") { because("GHSA fixed in 0.9.6") }
            classpath("org.apache.commons:commons-lang3:3.18.0") { because("GHSA fixed in 3.18.0") }
            classpath("org.apache.httpcomponents:httpclient:4.5.14") { because("GHSA fixed in 4.5.13") }
        }
    }
}

plugins {
    id("com.android.application") version "9.5.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
