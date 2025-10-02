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
		sourceCompatibility = JavaVersion.VERSION_17
		targetCompatibility = JavaVersion.VERSION_17
//		coreLibraryDesugaringEnabled = true
	}

	kotlin {
		compilerOptions {
			jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
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
	implementation(libs.androidx.core.ktx)
	implementation(libs.androidx.appcompat)
	implementation(libs.material)
	implementation(libs.androidx.constraintlayout)
	implementation(libs.androidx.navigation.fragment.ktx)
	implementation(libs.androidx.navigation.ui.ktx)
	testImplementation(libs.junit)
	androidTestImplementation(libs.androidx.junit)
	androidTestImplementation(libs.androidx.espresso.core)

	implementation(libs.okhttp)
	implementation(libs.okio)
	implementation(libs.retrofit)
	implementation(libs.converter.scalars)
	implementation(libs.rxjava)
	implementation(libs.adapter.rxjava3)
	implementation(libs.rxandroid)
	implementation(libs.converter.gson)

	implementation(files("libs/android-release.aar"))
}