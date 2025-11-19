package ru.ravel.ulwms.activity

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import ru.ravel.ulwms.databinding.ActivityScannerBinding


class ScannerActivity : AppCompatActivity() {

	private lateinit var binding: ActivityScannerBinding

	private val requestCameraPermission =
		registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
			if (granted) {
				startCamera()
			} else {
				finish()
			}
		}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		binding = ActivityScannerBinding.inflate(layoutInflater)
		setContentView(binding.root)
		if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
			startCamera()
		} else {
			requestCameraPermission.launch(Manifest.permission.CAMERA)
		}
	}

	private fun startCamera() {
		val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

		cameraProviderFuture.addListener({
			val cameraProvider = cameraProviderFuture.get()
			val preview = androidx.camera.core.Preview.Builder().build()

			preview.setSurfaceProvider(binding.previewView.surfaceProvider)

			val barcodeScanner = BarcodeScanning.getClient()

			val analysis = ImageAnalysis.Builder()
				.setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
				.build()

			analysis.setAnalyzer(ContextCompat.getMainExecutor(this)) { imageProxy ->
				processImage(barcodeScanner, imageProxy)
			}

			val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

			cameraProvider.unbindAll()
			cameraProvider.bindToLifecycle(
				this, cameraSelector, preview, analysis
			)

		}, ContextCompat.getMainExecutor(this))
	}

	@SuppressLint("UnsafeExperimentalUsageError")
	private fun processImage(
		scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
		imageProxy: ImageProxy
	) {
		val mediaImage = imageProxy.image ?: return

		val image = InputImage.fromMediaImage(
			mediaImage,
			imageProxy.imageInfo.rotationDegrees
		)

		scanner.process(image).addOnSuccessListener { barcodes ->
				for (barcode in barcodes) {
					val value = barcode.rawValue ?: continue
					val data = Intent().apply {
						putExtra("qr", value)
					}
					setResult(RESULT_OK, data)
					finish()
				}
			}
			.addOnCompleteListener {
				imageProxy.close()
			}
	}
}
