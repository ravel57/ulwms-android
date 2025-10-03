plugins {
	alias(libs.plugins.android.application)
	alias(libs.plugins.kotlin.android)
}

android {
	namespace = "ru.ravel.ulwms"
	compileSdk = 36

	defaultConfig {
		applicationId = "ru.ravel.ulwms"
		minSdk = 26
		targetSdk = 36
		versionCode = 1
		versionName = "1.0"

		testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
	}

	buildTypes {
		release {
			isMinifyEnabled = false
			proguardFiles(
				getDefaultProguardFile("proguard-android-optimize.txt"),
				"proguard-rules.pro"
			)
		}
	}

	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_1_8
		targetCompatibility = JavaVersion.VERSION_1_8
//		coreLibraryDesugaringEnabled = true
	}

	kotlin {
		compilerOptions {
			jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
		}
	}

	buildFeatures {
		viewBinding = true
	}

	packaging {
		resources {
			excludes += "kotlin/internal/internal.kotlin_builtins"
			excludes += "META-INF/*.kotlin_module"
			excludes += "META-INF/*.version"
		}
	}
}

dependencies {
//	core
	implementation(libs.androidx.core.ktx)
	implementation(libs.androidx.appcompat)
	implementation(libs.androidx.constraintlayout)
	implementation(libs.androidx.navigation.fragment.ktx)
	implementation(libs.androidx.navigation.ui.ktx)

	// Kotlin
	implementation(libs.jetbrains.kotlin.stdlib)

	// Jetpack Compose
	implementation(libs.androidx.activity.compose)
	implementation(libs.androidx.ui)
	implementation(libs.ui.tooling.preview)
	implementation(libs.material)
//	implementation(libs.androidx.material3)

//	Коррутины для фоновой загрузки JSON с сервера (опционально)
	implementation(libs.kotlinx.coroutines.android)

//	lcpe
	implementation(libs.okhttp)
	implementation(libs.okio)
	implementation(libs.retrofit)
	implementation(libs.converter.scalars)
	implementation(libs.rxjava)
	implementation(libs.adapter.rxjava3)
	implementation(libs.rxandroid)
	implementation(libs.converter.gson)
	implementation(libs.jackson.databind)
	implementation(libs.jackson.module.kotlin)
	implementation(libs.snakeyaml)

//	lib
	implementation(files("libs/android-release.aar"))

//	Для отладки UI
	debugImplementation(libs.androidx.ui.tooling)
	debugImplementation(libs.androidx.ui.test.manifest)

//	tests
	testImplementation(libs.junit)
	androidTestImplementation(libs.androidx.junit)
	androidTestImplementation(libs.androidx.espresso.core)
}