/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

import com.tom.rv2ide.build.config.BuildConfig


plugins {
    id("com.android.library")
    id("kotlin-android")
}



android {
    namespace = "${BuildConfig.packageName}.javac.services"

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    packaging {
        resources {
            // jdk-compiler.jar is present both as a transitive dep from the
            // composite 'javac' module and as a direct fileTree("libs") dep.
            // Pick first to avoid mergeDebugJavaResource conflicts.
            pickFirsts += "META-INF/**"
            pickFirsts += "openjdk/**"
            pickFirsts += "javac/**"
            pickFirsts += "sun/**"
            pickFirsts += "com/sun/**"
        }
    }
}

dependencies {
    api(libs.composite.javac)
    // jdk-compiler.jar must be dexed into the APK. Using fileTree("libs")
    // is the most reliable way for AGP to include it in dex output.
    api(fileTree("libs"))

    implementation(libs.common.kotlin)
    implementation(libs.common.utilcode)
    implementation(libs.google.guava)

    implementation(projects.core.common)
    implementation(projects.logging.logger)

}