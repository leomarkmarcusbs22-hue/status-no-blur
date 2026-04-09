package com.statusnoblur.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.statusnoblur.app.databinding.ActivityMainBinding
import java.io.File
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var selectedUri: Uri? = null
    private var isVideo = false
    private var optimizedFile: File? = null

    // Image picker
    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            selectedUri = it
            isVideo = false
            showPreview(it, false)
            optimizeMedia()
        }
    }

    // Video picker
    private val pickVideo = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            selectedUri = it
            isVideo = true
            showPreview(it, true)
            optimizeMedia()
        }
    }

    // Permission request
    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (!allGranted) {
            Toast.makeText(this, R.string.permission_required, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkPermissions()
        setupButtons()
    }

    private fun checkPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            requestPermission.launch(needed.toTypedArray())
        }
    }

    private fun setupButtons() {
        binding.btnPickImage.setOnClickListener {
            pickImage.launch("image/*")
        }

        binding.btnPickVideo.setOnClickListener {
            pickVideo.launch("video/*")
        }

        binding.btnShare.setOnClickListener {
            shareToWhatsApp()
        }
    }

    private fun showPreview(uri: Uri, isVideo: Boolean) {
        binding.placeholderLayout.visibility = View.GONE
        binding.ivPreview.visibility = View.VISIBLE
        binding.ivPreview.setImageURI(uri)
        binding.ivPlayIcon.visibility = if (isVideo) View.VISIBLE else View.GONE
        binding.btnShare.isEnabled = false
        binding.tvStatus.visibility = View.GONE
    }

    private fun optimizeMedia() {
        val uri = selectedUri ?: return

        // Show processing overlay
        binding.processingOverlay.visibility = View.VISIBLE
        binding.btnPickImage.isEnabled = false
        binding.btnPickVideo.isEnabled = false

        thread {
            try {
                optimizedFile = if (isVideo) {
                    VideoProcessor.optimizeForWhatsAppStatus(this, uri)
                } else {
                    ImageProcessor.optimizeForWhatsAppStatus(this, uri)
                }

                runOnUiThread {
                    binding.processingOverlay.visibility = View.GONE
                    binding.btnPickImage.isEnabled = true
                    binding.btnPickVideo.isEnabled = true
                    binding.btnShare.isEnabled = true

                    // Show status badge
                    binding.tvStatus.visibility = View.VISIBLE
                    binding.tvStatus.text = if (isVideo) "VIDEO READY" else "IMAGE READY"

                    // Show optimized preview for images
                    if (!isVideo) {
                        optimizedFile?.let { file ->
                            binding.ivPreview.setImageURI(Uri.fromFile(file))
                        }
                    }

                    Toast.makeText(
                        this,
                        if (isVideo) R.string.video_optimized else R.string.image_optimized,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.processingOverlay.visibility = View.GONE
                    binding.btnPickImage.isEnabled = true
                    binding.btnPickVideo.isEnabled = true
                    Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun shareToWhatsApp() {
        val file = optimizedFile ?: return

        val uri = FileProvider.getUriForFile(
            this,
            "${packageName}.provider",
            file
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = if (isVideo) "video/*" else "image/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            setPackage("com.whatsapp")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        // Check if WhatsApp is installed
        if (intent.resolveActivity(packageManager) != null) {
            startActivity(Intent.createChooser(intent, "Share to WhatsApp"))
        } else {
            // Try WhatsApp Business
            intent.setPackage("com.whatsapp.w4b")
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(Intent.createChooser(intent, "Share to WhatsApp"))
            } else {
                Toast.makeText(this, R.string.no_whatsapp, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up optimized files
        File(cacheDir, "optimized").deleteRecursively()
    }
}
