plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
}

dependencies {
    implementation("com.ibm.icu:icu4j:77.1")
    testImplementation("junit:junit:4.13.2")
}

gradlePlugin {
    plugins {
        create("l10n") {
            id = "sidespeed.l10n"
            implementationClass = "sidespeed.l10n.L10nPlugin"
        }
    }
}
